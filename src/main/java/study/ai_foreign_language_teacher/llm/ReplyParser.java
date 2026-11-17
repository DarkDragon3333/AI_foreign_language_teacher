package study.ai_foreign_language_teacher.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * Шаг «валидация JSON» из педагогического контура: разбирает ответ модели и приводит его к контракту.
 * Модели не доверяем: уровень, режим и support_level берём из сессии, пустые блоки убираем.
 */
@Component
public class ReplyParser {

    private static final Logger log = LoggerFactory.getLogger(ReplyParser.class);
    static final String FALLBACK_TEXT = "Sorry, I lost my train of thought. Could you say that again?";

    private final ObjectMapper mapper;

    public ReplyParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TeacherReply parse(String raw, Cefr cefr, LessonMode mode, int supportLevel) {
        return parse(raw, null, cefr, mode, supportLevel);
    }

    /**
     * @param streamedReplyEn текст reply_en, который уже ушёл ученику во время стриминга. Если JSON оборвался
     *                        (упёрлись в num_predict), сохраняем то, что ученик успел увидеть.
     */
    public TeacherReply parse(String raw, String streamedReplyEn, Cefr cefr, LessonMode mode, int supportLevel) {
        TeacherReply parsed = tryParse(raw);
        if (parsed != null && blank(parsed.replyEn()) && !blank(parsed.nextPrompt())) {
            // модель положила всю реплику в next_prompt (типично для первого хода) — это нормальный ответ
            parsed = new TeacherReply(parsed.nextPrompt(), parsed.replyRu(), parsed.correction(), parsed.explanation(),
                    null, parsed.cardsToReview(), parsed.mode(), parsed.cefr(), parsed.supportLevel());
        }
        if (parsed == null || blank(parsed.replyEn())) {
            log.warn("LLM reply is not valid contract JSON, using fallback. Raw: {}", abbreviate(raw));
            String text = !blank(streamedReplyEn) ? streamedReplyEn.strip()
                    : parsed == null && looksLikePlainText(raw) ? raw.strip() : FALLBACK_TEXT;
            return new TeacherReply(text, null, null, null, null, List.of(), mode.json(), cefr.name(), supportLevel);
        }
        String replyEn = parsed.replyEn().strip();
        return new TeacherReply(
                replyEn,
                clean(parsed.replyRu()),
                normalize(parsed.correction()),
                normalize(parsed.explanation()),
                dedupeQuestion(replyEn, clean(parsed.nextPrompt())),
                parsed.cardsToReview() == null ? List.of()
                        : parsed.cardsToReview().stream().filter(s -> !blank(s)).map(String::strip).distinct().limit(5).toList(),
                mode.json(),
                cefr.name(),
                supportLevel);
    }

    /** Модель выдала JSON по контракту (а не обрывок или посторонний текст). */
    public boolean isContract(String raw) {
        TeacherReply parsed = tryParse(raw);
        return parsed != null && (!blank(parsed.replyEn()) || !blank(parsed.nextPrompt()));
    }

    /** Маленькие модели любят повторить вопрос и в reply_en, и в next_prompt — второй раз не показываем. */
    static String dedupeQuestion(String replyEn, String nextPrompt) {
        if (nextPrompt == null) {
            return null;
        }
        String reply = replyEn.toLowerCase().replaceAll("\\s+", " ");
        String next = nextPrompt.toLowerCase().replaceAll("\\s+", " ").replaceAll("[?!. ]+$", "");
        return !next.isEmpty() && reply.contains(next) ? null : nextPrompt;
    }

    private TeacherReply tryParse(String raw) {
        if (blank(raw)) {
            return null;
        }
        String json = raw.strip();
        // на случай модели без structured output: ```json ... ``` или текст вокруг объекта
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return fromWire(mapper.readValue(json.substring(start, end + 1), LlmReply.class));
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Формат модели → контракт интерфейса. Разметку **жирным** и *курсивом* убираем: интерфейс её не показывает. */
    private static TeacherReply fromWire(LlmReply w) {
        TeacherReply.Correction correction = null;
        if (w.correction() != null) {
            List<TeacherReply.ErrorItem> errors = w.correction().errors() == null ? null : w.correction().errors().stream()
                    .filter(Objects::nonNull)
                    .map(e -> new TeacherReply.ErrorItem(plain(e.fragment()), plain(e.rule()), plain(e.tip())))
                    .toList();
            correction = new TeacherReply.Correction(plain(w.correction().original()), plain(w.correction().fixedText()), errors);
        }
        TeacherReply.Explanation explanation = w.explanation() == null ? null : new TeacherReply.Explanation(
                plain(w.explanation().briefRule()), plain(w.explanation().exampleCorrect()), plain(w.explanation().exampleWrong()),
                null, null);
        String question = plain(w.followUpQuestion());
        // answer_ru пишется до вопроса и переводит только ответ. Вопрос переводится в follow_up_ru.
        String russian = blank(question) ? plain(w.answerRu()) : joinRu(plain(w.answerRu()), plain(w.followUpRu()));
        return new TeacherReply(plain(w.answerEn()), russian, correction, explanation,
                question, List.of(), null, null, null);
    }

    /**
     * Склеивает перевод ответа и перевод вопроса. Если модель уже включила вопрос в перевод ответа,
     * второй раз его не добавляем.
     */
    static String joinRu(String answerRu, String questionRu) {
        if (blank(questionRu)) {
            return blank(answerRu) ? null : answerRu;
        }
        if (blank(answerRu)) {
            return questionRu;
        }
        String normAnswer = answerRu.toLowerCase().replaceAll("\\s+", " ");
        String normQuestion = questionRu.toLowerCase().replaceAll("\\s+", " ").replaceAll("[?!.… ]+$", "");
        if (!normQuestion.isEmpty() && normAnswer.contains(normQuestion)) {
            return answerRu;
        }
        return answerRu.strip() + " " + questionRu.strip();
    }

    private static String plain(String s) {
        return s == null ? null : s.replace("**", "").replaceAll("(?<![\\p{L}\\p{N}])\\*(?=\\S)|(?<=\\S)\\*(?![\\p{L}\\p{N}])", "");
    }

    private static TeacherReply.Correction normalize(TeacherReply.Correction c) {
        if (c == null) {
            return null;
        }
        String original = clean(c.original());
        String corrected = clean(c.corrected());
        List<TeacherReply.ErrorItem> errors = c.errors() == null ? List.<TeacherReply.ErrorItem>of() : c.errors().stream()
                .filter(Objects::nonNull)
                .map(e -> new TeacherReply.ErrorItem(clean(e.span()), clean(e.rule()), clean(e.hint())))
                .filter(e -> e.span() != null || e.rule() != null || e.hint() != null)
                .toList();
        // в новом формате модель не повторяет исходную фразу: сравнивать её с текстом ученика будет CorrectionPolicy
        boolean nothingChanged = corrected == null || corrected.equals(original);
        if (nothingChanged && errors.isEmpty()) {
            return null; // ошибок нет — блок исправления не показываем
        }
        return new TeacherReply.Correction(original, corrected, errors);
    }

    private static TeacherReply.Explanation normalize(TeacherReply.Explanation e) {
        if (e == null) {
            return null;
        }
        String simple = clean(e.simple());
        String ok = clean(e.exampleOk());
        String bad = clean(e.exampleBad());
        String title = clean(e.title());
        String trap = clean(e.trap());
        if (simple == null && ok == null && bad == null && title == null && trap == null) {
            return null;
        }
        return new TeacherReply.Explanation(simple, ok, bad, title, trap);
    }

    private static boolean looksLikePlainText(String raw) {
        return !blank(raw) && raw.indexOf('{') < 0 && raw.length() < 2000;
    }

    private static String clean(String s) {
        return blank(s) ? null : s.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
