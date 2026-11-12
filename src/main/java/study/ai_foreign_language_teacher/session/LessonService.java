package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.llm.PromptBuilder;
import study.ai_foreign_language_teacher.llm.ReplyParser;
import study.ai_foreign_language_teacher.llm.ReplySchema;
import study.ai_foreign_language_teacher.llm.ReplyTextStream;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.MessageRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.nlp.GrammarChecker;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Педагогический контур (раздел 7): реплика ученика → контекст → LLM → валидация → запись → ответ.
 * Карточки правил и FSRS встанут в этот же метод на шаге 4.
 */
@Service
public class LessonService {

    private static final int MAX_TEXT_LENGTH = 2000;

    private final ProfileRepository profiles;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final ErrorRepository errors;
    private final GrammarChecker checker;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ReplyParser parser;
    private final ObjectMapper mapper;
    private final TutorProperties props;

    public LessonService(ProfileRepository profiles, SessionRepository sessions, MessageRepository messages,
                         ErrorRepository errors, GrammarChecker checker, LlmClient llm, PromptBuilder prompts, ReplyParser parser, ObjectMapper mapper,
                         TutorProperties props) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.messages = messages;
        this.errors = errors;
        this.checker = checker;
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.mapper = mapper;
        this.props = props;
    }

    public LessonSession startSession(String cefr, String topic, String mode) {
        UserProfile profile = profiles.getOrCreateDefault();
        Cefr level = cefr == null || cefr.isBlank() ? profile.cefr() : Cefr.parse(cefr);
        String cleanTopic = topic == null || topic.isBlank() ? null : topic.strip();
        if (cleanTopic != null && cleanTopic.length() > 200) {
            throw new IllegalArgumentException("Topic is too long (max 200 characters)");
        }
        return sessions.create(profile.id(), LessonMode.parse(mode), cleanTopic, level);
    }

    public LessonSession getSession(long id) {
        return sessions.find(id).orElseThrow(() -> new NotFoundException("Session " + id + " not found"));
    }

    public List<LessonSession> recentSessions(int limit) {
        return sessions.recent(profiles.getOrCreateDefault().id(), limit);
    }

    public List<ChatMessage> history(long sessionId) {
        getSession(sessionId);
        return messages.all(sessionId);
    }

    public LessonSession endSession(long id) {
        getSession(id);
        sessions.end(id);
        return getSession(id);
    }

    public LessonSession changeLevel(long id, String cefr) {
        getSession(id);
        sessions.updateCefr(id, Cefr.parse(cefr));
        return getSession(id);
    }

    /**
     * Один ход урока.
     *
     * @param text          реплика ученика; пустая — учитель сам начинает урок
     * @param hintRequested ученик нажал «подсказка»
     * @param onText        получает текст ответа учителя по мере генерации (может быть null)
     */
    public Turn turn(long sessionId, String text, boolean hintRequested, Consumer<String> onText) {
        LessonSession session = getSession(sessionId);
        if (session.endedAt() != null) {
            throw new IllegalArgumentException("Session " + sessionId + " is already finished");
        }
        String userText = text == null ? "" : text.strip();
        if (userText.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Message is too long (max " + MAX_TEXT_LENGTH + " characters)");
        }

        // «Поддержка, если не получается»: каждая просьба о помощи поднимает ступень, обычный ответ сбрасывает.
        boolean stuck = hintRequested || StuckDetector.isStuck(userText);
        int supportLevel = stuck ? Math.min(3, session.supportLevel() + 1) : 0;
        if (supportLevel != session.supportLevel()) {
            sessions.updateSupportLevel(sessionId, supportLevel);
            session = getSession(sessionId);
        }

        ChatMessage userMessage = null;
        if (!userText.isEmpty()) {
            userMessage = messages.add(sessionId, Role.USER, userText, null);
        } else if (hintRequested) {
            userMessage = messages.add(sessionId, Role.USER, "I need a hint, please.", null);
        }

        UserProfile profile = profiles.getOrCreateDefault();

        // LanguageTool первый: детерминированные ошибки находим до LLM и передаём ей как факты
        boolean checkable = !userText.isEmpty() && !stuck;
        List<LtMatch> found = checkable ? checker.check(userText) : List.of();

        List<ChatMessage> history = messages.lastN(sessionId, props.lesson().historyMessages());
        List<ErrorEvent> weak = errors.top(profile.id(), 3);
        // что учитель уже говорил: чтобы не спрашивал одно и то же по кругу
        List<String> teacherTexts = history.stream().filter(m -> m.role() == Role.ASSISTANT).map(ChatMessage::text).toList();
        List<String> recent = teacherTexts.subList(Math.max(0, teacherTexts.size() - 4), teacherTexts.size());
        List<LlmMessage> prompt = prompts.build(profile, session, history, stuck, checkable ? found : null, weak, recent);

        StringBuilder streamed = new StringBuilder();
        Consumer<String> stream = new ReplyTextStream("reply_en", chunk -> {
            streamed.append(chunk);
            if (onText != null) {
                onText.accept(chunk);
            }
        });
        String raw = llm.chat(prompt, session.mode().temperature(), ReplySchema.schema(), stream);

        TeacherReply parsed = ReplyPolish.dropEcho(
                parser.parse(raw, streamed.toString(), session.cefr(), session.mode(), supportLevel), userText);

        // Повтор или эхо — одна повторная попытка с более высокой температурой. Ученик увидит уже второй вариант.
        if (ReplyPolish.isStale(parsed, userText, recent)) {
            String second = llm.chat(prompt, Math.min(1.0, session.mode().temperature() + 0.3), ReplySchema.schema(), null);
            TeacherReply retry = ReplyPolish.dropEcho(
                    parser.parse(second, null, session.cefr(), session.mode(), supportLevel), userText);
            if (!ReplyPolish.isStale(retry, userText, recent)) {
                parsed = retry;
            }
        }

        // Исправление показываем, только если оно подтверждено LT или модель точно указала ошибку
        found = CorrectionPolicy.confirm(found, parsed.correction());
        TeacherReply.Correction correction = checkable ? CorrectionPolicy.decide(userText, parsed.correction(), found) : null;
        // к опечатке правило не нужно; правило показываем к грамматической ошибке, по просьбе и в режиме объяснения
        boolean onlySpelling = correction != null && !found.isEmpty() && found.stream().allMatch(LtMatch::spelling)
                && correction.errors().size() == found.size();
        boolean explain = (correction != null && !onlySpelling) || stuck || session.mode() == LessonMode.EXPLAIN;
        TeacherReply reply = new TeacherReply(parsed.replyEn(), parsed.replyRu(), correction,
                explain ? parsed.explanation() : null, parsed.nextPrompt(), parsed.cardsToReview(),
                parsed.mode(), parsed.cefr(), parsed.supportLevel());

        if (userMessage != null && correction != null) {
            journal(profile.id(), userMessage.id(), found, correction);
        }
        ChatMessage saved = messages.add(sessionId, Role.ASSISTANT, reply.displayText(), mapper.writeValueAsString(reply));
        return new Turn(session, userMessage, saved, reply);
    }

    /** Журнал ошибок: находки LT — под id правила LT, остальное — под названием правила от модели. */
    private void journal(long userId, long messageId, List<LtMatch> found, TeacherReply.Correction correction) {
        errors.saveMatches(messageId, found);
        for (LtMatch m : found) {
            errors.record(userId, m.ruleId(), "lt", m.category(), m.title().isBlank() ? m.category() : m.title(), m.span());
        }
        List<TeacherReply.ErrorItem> items = correction.errors();
        for (TeacherReply.ErrorItem e : items.subList(Math.min(found.size(), items.size()), items.size())) {
            String slug = e.rule().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
            if (!slug.isEmpty()) {
                errors.record(userId, "LLM_" + slug, "llm", "Grammar", e.rule(), e.span());
            }
        }
    }

    public List<ErrorEvent> errorJournal(int limit) {
        return errors.top(profiles.getOrCreateDefault().id(), limit);
    }

    public record Turn(LessonSession session, ChatMessage userMessage, ChatMessage teacherMessage, TeacherReply reply) {
    }
}
