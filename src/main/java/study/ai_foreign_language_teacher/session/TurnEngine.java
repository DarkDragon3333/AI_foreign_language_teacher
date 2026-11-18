package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.llm.PromptBuilder;
import study.ai_foreign_language_teacher.llm.ReplyParser;
import study.ai_foreign_language_teacher.llm.ReplySchema;
import study.ai_foreign_language_teacher.llm.ReplyTextStream;
import study.ai_foreign_language_teacher.memory.rules.RuleRetriever;
import study.ai_foreign_language_teacher.nlp.AgreementPatterns;
import study.ai_foreign_language_teacher.nlp.GrammarChecker;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.util.List;
import java.util.function.Consumer;

/**
 * Педагогический контур одного хода без записи в базу:
 * LanguageTool → промпт → LLM → разбор → защита от эха и повторов → решение об исправлении.
 * Урок (LessonService) добавляет к этому сохранение; стенд сравнения моделей вызывает контур напрямую.
 */
@Component
public class TurnEngine {

    private final GrammarChecker checker;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ReplyParser parser;
    private final RuleRetriever rules;

    public TurnEngine(GrammarChecker checker, LlmClient llm, PromptBuilder prompts, ReplyParser parser, RuleRetriever rules) {
        this.checker = checker;
        this.llm = llm;
        this.prompts = prompts;
        this.parser = parser;
        this.rules = rules;
    }

    /**
     * @param history  последние реплики по порядку; если ученик что-то написал, его реплика — последняя
     * @param userText реплика ученика; пустая — учитель начинает урок или даёт подсказку
     * @param stuck    ученик просит помощи
     * @param seed     фиксированная случайность для сравнения моделей; null в обычном уроке
     */
    public record Input(UserProfile profile, LessonSession session, List<ChatMessage> history, String userText,
                        boolean stuck, List<ErrorEvent> weak, Integer seed) {
    }

    /**
     * @param reply     то, что увидит ученик
     * @param trusted   находки LanguageTool, которым поверили
     * @param firstRaw  сырой текст первой попытки модели
     * @param fromModel исправление, как его предложила сама модель (до фильтров)
     * @param echoDropped модель в первой попытке пересказала реплику ученика вместо ответа
     */
    public record Output(TeacherReply reply, List<LtMatch> trusted, String firstRaw, TeacherReply.Correction fromModel,
                         boolean fallback, boolean echoDropped, boolean retried, boolean staleAfterRetry,
                         int llmCalls, long evalTokens, long evalNanos, long millis) {
    }

    public Output run(Input in, Consumer<String> onText) {
        long started = System.nanoTime();
        LessonSession session = in.session();
        String userText = in.userText() == null ? "" : in.userText();
        int supportLevel = session.supportLevel();

        // LanguageTool первый: детерминированные ошибки находим до LLM и передаём ей как факты
        boolean checkable = !userText.isEmpty() && !in.stuck();
        List<LtMatch> found = checkable ? AgreementPatterns.supplement(userText, checker.check(userText)) : List.of();
        List<RuleCard> cards = rules.retrieve(session.cefr(), session.mode(), session.topic(), userText,
                checkable ? found : null, in.stuck());

        // что учитель уже говорил: чтобы не спрашивал одно и то же по кругу
        List<String> teacherTexts = in.history().stream().filter(m -> m.role() == Role.ASSISTANT).map(ChatMessage::text).toList();
        List<String> recent = teacherTexts.subList(Math.max(0, teacherTexts.size() - 4), teacherTexts.size());
        List<LlmMessage> prompt = prompts.build(in.profile(), session, in.history(), in.stuck(),
                checkable ? found : null, in.weak(), recent, cards, session.id());

        StringBuilder streamed = new StringBuilder();
        Consumer<String> stream = new ReplyTextStream("answer_en", chunk -> {
            streamed.append(chunk);
            if (onText != null) {
                onText.accept(chunk);
            }
        });
        double temperature = session.mode().temperature();
        LlmClient.Call first = llm.chat(session.model(), prompt, temperature, ReplySchema.schema(), in.seed(), stream);
        long evalTokens = first.evalTokens();
        long evalNanos = first.evalNanos();
        int calls = 1;

        boolean fallback = !parser.isContract(first.text());
        TeacherReply beforeEcho = parser.parse(first.text(), streamed.toString(), session.cefr(), session.mode(), supportLevel);
        TeacherReply.Correction fromModel = beforeEcho.correction();
        TeacherReply parsed = ReplyPolish.dropEcho(beforeEcho, userText);
        // эхо считаем по самой модели, даже если подменить его оказалось нечем
        boolean echoDropped = !userText.isEmpty() && ReplyPolish.similar(beforeEcho.replyEn(), userText);

        // Повтор или эхо — одна повторная попытка с более высокой температурой. Ученик увидит уже второй вариант.
        boolean retried = false;
        boolean stale = ReplyPolish.isStale(parsed, userText, recent);
        if (stale) {
            retried = true;
            LlmClient.Call second = llm.chat(session.model(), prompt, Math.min(1.0, temperature + 0.3), ReplySchema.schema(),
                    in.seed() == null ? null : in.seed() + 1, null);
            evalTokens += second.evalTokens();
            evalNanos += second.evalNanos();
            calls++;
            TeacherReply retry = ReplyPolish.dropEcho(
                    parser.parse(second.text(), null, session.cefr(), session.mode(), supportLevel), userText);
            if (!ReplyPolish.isStale(retry, userText, recent)) {
                parsed = retry;
                stale = false;
            }
        }

        // Исправление показываем, только если оно подтверждено LT или модель точно указала ошибку
        List<LtMatch> trusted = CorrectionPolicy.confirm(found, parsed.correction());
        TeacherReply.Correction correction = checkable ? CorrectionPolicy.decide(userText, parsed.correction(), trusted) : null;
        // к опечатке правило не нужно; правило показываем к грамматической ошибке, по просьбе и в режиме объяснения
        boolean onlySpelling = correction != null && !trusted.isEmpty() && trusted.stream().allMatch(LtMatch::spelling)
                && correction.errors().size() == trusted.size();
        boolean explain = (correction != null && !onlySpelling) || in.stuck() || session.mode() == LessonMode.EXPLAIN;
        boolean asked = RuleRetriever.grammarQuestion(userText);
        boolean genericExplanation = in.stuck() || session.mode() == LessonMode.EXPLAIN;
        TeacherReply.Explanation explanation = parsed.explanation();
        String replyRu = parsed.replyRu();
        List<String> slugs = List.of();
        if (!cards.isEmpty() && (explain || asked)) {
            RuleCard card = cards.get(0);
            String simple = session.cefr().ordinal() <= Cefr.A2.ordinal() ? card.ruleRu() : card.rule();
            explanation = new TeacherReply.Explanation(simple, card.exampleOk(), card.exampleBad(), card.title(), card.l1Trap());
            slugs = cards.stream().map(RuleCard::slug).toList();
            if ((replyRu == null || replyRu.isBlank()) && (in.stuck() || session.cefr().ordinal() <= Cefr.A2.ordinal())) {
                replyRu = card.ruleRu();
            }
        } else if (explain && ExplanationGate.quotesLearner(userText, explanation, genericExplanation)) {
            explanation = parsed.explanation();
        } else {
            explanation = null;
        }
        TeacherReply reply = new TeacherReply(parsed.replyEn(), replyRu, correction,
                explanation, parsed.nextPrompt(), slugs,
                parsed.mode(), parsed.cefr(), parsed.supportLevel());

        return new Output(reply, trusted, first.text(), fromModel, fallback, echoDropped, retried, stale,
                calls, evalTokens, evalNanos, (System.nanoTime() - started) / 1_000_000);
    }
}
