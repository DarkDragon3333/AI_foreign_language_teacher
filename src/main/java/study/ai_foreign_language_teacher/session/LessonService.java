package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LearningPlan;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.SessionPlanState;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.SessionSummary;
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
import study.ai_foreign_language_teacher.memory.rules.ReviewService;
import study.ai_foreign_language_teacher.nlp.GrammarChecker;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import study.ai_foreign_language_teacher.schedule.ScheduleService;
import study.ai_foreign_language_teacher.speech.SttPromptBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Педагогический контур (раздел 7): реплика ученика → контекст → LLM → валидация → запись → ответ.
 * Карточка правила подкладывается в запрос внутри TurnEngine. Ошибка по ней ставит карточку в очередь FSRS.
 */
@Service
public class LessonService {

    private static final int MAX_TEXT_LENGTH = 2000;

    private final ProfileRepository profiles;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final ErrorRepository errors;
    private final TurnEngine engine;
    private final ReviewService reviews;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final TutorProperties props;
    private final ScenarioService scenarios;
    private final ScheduleService schedule;
    private final StructuredLessonService structured;
    private final PlacementLessonService placement;

    public LessonService(ProfileRepository profiles, SessionRepository sessions, MessageRepository messages,
                         ErrorRepository errors, TurnEngine engine, ReviewService reviews, LlmClient llm,
                         ObjectMapper mapper, TutorProperties props, ScenarioService scenarios,
                         ScheduleService schedule, StructuredLessonService structured,
                         PlacementLessonService placement) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.messages = messages;
        this.errors = errors;
        this.engine = engine;
        this.reviews = reviews;
        this.llm = llm;
        this.mapper = mapper;
        this.props = props;
        this.scenarios = scenarios;
        this.schedule = schedule;
        this.structured = structured;
        this.placement = placement;
    }

    public LessonSession startSession(String cefr, String topic, String mode, String model, Integer durationMin) {
        UserProfile profile = profiles.getOrCreateDefault();
        Cefr level = cefr == null || cefr.isBlank() ? profile.cefr() : Cefr.parse(cefr);
        String cleanTopic = topic == null || topic.isBlank() ? null : topic.strip();
        if (cleanTopic != null && cleanTopic.length() > 200) {
            throw new IllegalArgumentException("Topic is too long (max 200 characters)");
        }
        String cleanModel = model == null || model.isBlank() ? null : model.strip();
        if (cleanModel != null && llm.models().stream().noneMatch(m -> m.name().equals(cleanModel))) {
            throw new IllegalArgumentException("Model " + cleanModel + " is not installed in Ollama");
        }
        LessonMode lessonMode = LessonMode.parse(mode);
        if (lessonMode == LessonMode.STRUCTURED && durationMin == null) {
            throw new IllegalArgumentException("durationMin is required for structured mode (15 or 25)");
        }
        if (lessonMode == LessonMode.ROLEPLAY) {
            if (cleanTopic == null) {
                throw new IllegalArgumentException("Choose a scenario (topic = scenario slug) for roleplay mode");
            }
            scenarios.findForStart(cleanTopic);
        }
        var plan = lessonMode == LessonMode.STRUCTURED
                ? structured.initPlan(durationMin, java.time.Instant.now())
                : lessonMode == LessonMode.PLACEMENT ? placement.initPlan() : null;
        if (lessonMode == LessonMode.PLACEMENT) {
            cleanTopic = "placement";
        }
        LessonSession session = sessions.create(profile.id(), lessonMode, cleanTopic, level, cleanModel, plan);
        if (lessonMode == LessonMode.ROLEPLAY) {
            scenarios.start(session.id(), cleanTopic);
        }
        return session;
    }

    public StructuredLessonService.PhaseView phaseView(LessonSession session) {
        return structured.view(session).orElse(null);
    }

    public PlacementLessonService.PlacementView placementView(LessonSession session) {
        return placement.view(session).orElse(null);
    }

    public record SessionExtras(StructuredLessonService.PhaseView structured,
                                PlacementLessonService.PlacementView placement) {
        public static SessionExtras of(LessonSession session, LessonService lessons) {
            return new SessionExtras(lessons.phaseView(session), lessons.placementView(session));
        }
    }

    public LessonSession applyPlacementManual(long sessionId, String cefr) {
        LessonSession session = getSession(sessionId);
        if (session.mode() != LessonMode.PLACEMENT) {
            throw new IllegalArgumentException("Session is not a placement onboarding");
        }
        UserProfile profile = profiles.getOrCreateDefault();
        return placement.applyManualLevel(session, profile, cefr);
    }

    public void applyPlacementPlan(LearningPlan plan) {
        schedule.applyLearningPlan(plan);
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

    /** Контекст для Whisper: тема, имена из чата (в т.ч. Vasia). */
    public SpeechContext speechContext(long sessionId) {
        LessonSession session = getSession(sessionId);
        List<ChatMessage> recent = messages.lastN(sessionId, props.lesson().historyMessages());
        return new SpeechContext(
                SttPromptBuilder.forLesson(recent, session.topic()),
                SttPromptBuilder.namesFrom(recent));
    }

    public record SpeechContext(String prompt, Set<String> names) {
    }

    public LessonSession endSession(long id) {
        LessonSession session = getSession(id);
        if (session.endedAt() != null) {
            scenarios.clear(id);
            return session;
        }
        Instant endedAt = Instant.now();
        SessionSummary summary = SessionSummaries.build(session, endedAt, messages, errors);
        sessions.end(id, summary);
        if (getSession(id).mode() != LessonMode.PLACEMENT) {
            schedule.onSessionEnded(getSession(id));
        }
        scenarios.clear(id);
        return getSession(id);
    }

    public record DailyProgress(int minutesDone, int goalMinutes, boolean goalMet) {
    }

    public DailyProgress todayProgress() {
        UserProfile profile = profiles.getOrCreateDefault();
        LocalDate today = LocalDate.now();
        int minutes = sessions.practicedMinutesOnDay(profile.id(), today);
        int openMinutes = sessions.findOpen(profile.id())
                .map(open -> SessionSummaries.durationMinutes(open.startedAt(), Instant.now()))
                .orElse(0);
        minutes += openMinutes;
        int goal = profile.dailyGoalMin();
        return new DailyProgress(minutes, goal, minutes >= goal);
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
        return turn(sessionId, text, hintRequested, onText, null, null);
    }

    public Turn turn(long sessionId, String text, boolean hintRequested, Consumer<String> onText,
                     Consumer<String> onPlacementPrompt, Consumer<String> onPlacementPassage) {
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
            scenarios.onUserMessage(sessionId, session, userText);
        } else if (hintRequested) {
            userMessage = messages.add(sessionId, Role.USER, "I need a hint, please.", null);
        }

        List<ChatMessage> history = messages.lastN(sessionId, props.lesson().historyMessages());
        SessionPlanState placementBefore = null;
        if (!userText.isEmpty() && session.mode() == LessonMode.PLACEMENT) {
            placementBefore = session.placementPlan();
            String lastFollowUp = placement.lastFollowUpQuestion(history);
            session = placement.advanceAfterUser(session, userText, lastFollowUp);
        }
        session = structured.syncPhase(getSession(sessionId));

        UserProfile profile = profiles.getOrCreateDefault();
        history = messages.lastN(sessionId, props.lesson().historyMessages());
        TurnEngine.Output out;
        try {
            boolean placementStream = session.mode() == LessonMode.PLACEMENT;
            out = engine.run(
                    new TurnEngine.Input(profile, session, history, userText, stuck, errors.top(profile.id(), 3), null),
                    onText,
                    placementStream ? onPlacementPrompt : null,
                    placementStream ? onPlacementPassage : null);
        } catch (RuntimeException ex) {
            if (placementBefore != null) {
                sessions.updatePlanState(sessionId, placementBefore);
            }
            throw ex;
        }
        session = placement.ingestModelMeta(getSession(sessionId), out.firstRaw(), history);
        TeacherReply reply = out.reply();
        TeacherReply.Correction correction = reply.correction();
        List<LtMatch> found = out.trusted();

        if (userMessage != null && correction != null) {
            journal(profile.id(), userMessage.id(), found, correction);
            for (String slug : reply.cardsToReview()) {
                reviews.noteMistake(profile.id(), slug);
            }
        }
        ChatMessage saved = messages.add(sessionId, Role.ASSISTANT, reply.displayText(), mapper.writeValueAsString(reply));
        session = getSession(sessionId);
        return new Turn(session, userMessage, saved, reply, scenarios.progress(sessionId, session).orElse(null));
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

    public record Turn(LessonSession session, ChatMessage userMessage, ChatMessage teacherMessage, TeacherReply reply,
                       ScenarioService.Progress scenario) {
    }
}
