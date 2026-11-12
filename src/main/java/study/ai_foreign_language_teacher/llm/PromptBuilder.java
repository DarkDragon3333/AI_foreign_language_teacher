package study.ai_foreign_language_teacher.llm;

import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Собирает сообщения для LLM: system prompt (раздел 12) + короткая история + текущая реплика. */
@Component
public class PromptBuilder {

    static final String LESSON_START = "[The lesson starts now. Greet the learner in one sentence and open the topic with a question.]";

    private final ObjectMapper mapper;

    public PromptBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param history последние реплики в хронологическом порядке; текущая реплика ученика — последняя
     *                (или истории нет вовсе, если урок только начинается)
     */
    public List<LlmMessage> build(UserProfile profile, LessonSession session, List<ChatMessage> history, boolean stuck) {
        return build(profile, session, history, stuck, null, List.of());
    }

    /**
     * @param checker находки LanguageTool по последней реплике ученика; null — проверка не выполнялась
     *                (урок только начался, ученик пишет по-русски, LT выключен)
     * @param weak    частые ошибки ученика из журнала
     */
    public List<LlmMessage> build(UserProfile profile, LessonSession session, List<ChatMessage> history, boolean stuck,
                                  List<LtMatch> checker, List<ErrorEvent> weak) {
        return build(profile, session, history, stuck, checker, weak, List.of());
    }

    /** @param alreadyAsked вопросы учителя, которые нельзя задавать снова */
    public List<LlmMessage> build(UserProfile profile, LessonSession session, List<ChatMessage> history, boolean stuck,
                                  List<LtMatch> checker, List<ErrorEvent> weak, List<String> alreadyAsked) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt(profile, session, stuck) + checkerReport(checker) + weakPoints(weak)
                + alreadyAsked(alreadyAsked)));
        for (ChatMessage m : history) {
            messages.add(m.role() == Role.USER
                    ? LlmMessage.user(m.text())
                    : LlmMessage.assistant(compactAssistant(m)));
        }
        if (history.isEmpty() || history.get(history.size() - 1).role() != Role.USER) {
            messages.add(LlmMessage.user(LESSON_START));
        }
        return messages;
    }

    String systemPrompt(UserProfile profile, LessonSession session, boolean stuck) {
        String topic = session.topic() == null || session.topic().isBlank() ? "free choice — follow the learner's interests" : session.topic();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a friendly native-speaker English teacher in a one-to-one lesson.
                The learner's first language is Russian. The learner's level is CEFR %s.

                LEVEL RULES (%s):
                %s

                LESSON
                - Mode: %s. %s
                - Topic: %s.

                ALWAYS
                - Write correct, natural English. Never repeat the learner's mistake in your own words.
                - reply_en is YOUR OWN short reaction to what the learner said (1-2 sentences): answer their question, comment on it, or encourage them. It is not a corrected copy of the learner's message: never repeat or rephrase the learner's sentence in reply_en.
                - next_prompt is exactly one NEW question or task that moves the lesson forward. Never write the same sentence in reply_en and next_prompt, and never ask a question you have already asked.
                - If the learner asks you something, answer it in reply_en first.
                - If the learner's last message has a real mistake, fill "correction": original = the learner's whole message, corrected = the whole message with only the mistakes fixed, errors = the mistakes (span = the wrong words copied exactly from the learner's message, rule = short rule name, hint = one short tip). Never shorten, split or rephrase a correct sentence: style is not a mistake. If there is nothing to fix, set corrected equal to original and leave errors empty.
                - Fill "explanation" only when you explain a rule or the learner asks why: simple = the rule in one or two short sentences, example_ok = one correct example, example_bad = one typical wrong example. Otherwise leave its fields empty.
                - Only correct what you are sure about. Do not invent grammar rules.
                - reply_ru is Russian text. Leave it empty unless the level rules or the support rules ask for it.
                - Leave cards_to_review empty.
                """.formatted(session.cefr(), session.cefr(), session.cefr().teachingGuidance(),
                session.mode().json(), session.mode().instruction(), topic));

        sb.append("""

                EXAMPLE
                Learner: "I like play football with my friends."
                Good: reply_en = "That sounds fun! Football is a great way to see friends.", next_prompt = "How often do you play?"
                Bad: reply_en = "I like to play football with my friends." (it only repeats the learner)
                """);
        sb.append("\nSUPPORT\n").append(supportRule(session.supportLevel(), stuck));
        sb.append("\nAnswer with one JSON object only, following the given schema.\n");
        return sb.toString();
    }

    /** «LT первый; LLM объясняет размеченное» — находки проверяющего модель обязана принять. */
    static String checkerReport(List<LtMatch> checker) {
        if (checker == null) {
            return "";
        }
        if (checker.isEmpty()) {
            return """

                    GRAMMAR CHECKER
                    An automatic checker found no mistakes in the learner's last message. Assume it is correct.
                    Add a correction only for a clear grammar or word-choice mistake that you can quote exactly in errors.span.
                    """;
        }
        StringBuilder sb = new StringBuilder("""

                GRAMMAR CHECKER
                An automatic checker found these mistakes in the learner's last message. They are reliable: include every one
                in "correction", explain them in your own simple words, and do not argue with them.
                """);
        for (LtMatch m : checker) {
            if (m.spelling()) {
                continue;
            }
            sb.append("- \"").append(m.span()).append('"');
            if (m.firstReplacement() != null) {
                sb.append(" -> \"").append(m.firstReplacement()).append('"');
            }
            sb.append(": ").append(m.message()).append('\n');
        }
        List<LtMatch> typos = checker.stream().filter(LtMatch::spelling).toList();
        if (!typos.isEmpty()) {
            sb.append("Possible typos. Fix a word in \"corrected\" only if it is really misspelled. "
                    + "Names of people, places, brands and products are NOT typos: keep them exactly as the learner wrote them.\n");
            for (LtMatch m : typos) {
                sb.append("- \"").append(m.span()).append("\" (maybe \"").append(String.join("\" or \"", m.replacements())).append("\")\n");
            }
        }
        return sb.toString();
    }

    static String alreadyAsked(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nYOU ALREADY ASKED THESE. Do not ask them again, ask something new:\n");
        for (String q : questions) {
            sb.append("- ").append(q).append('\n');
        }
        return sb.toString();
    }

    static String weakPoints(List<ErrorEvent> weak) {
        if (weak == null || weak.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nLEARNER'S RECURRING MISTAKES (give a natural chance to practise them, do not lecture):\n");
        for (ErrorEvent e : weak) {
            sb.append("- ").append(e.title() == null || e.title().isBlank() ? e.category() : e.title());
            if (e.sampleSpan() != null) {
                sb.append(" (e.g. \"").append(e.sampleSpan()).append("\")");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Ступенчатые подсказки: 0 намёк → 1 правило → 2 пример → 3 правильный ответ. */
    static String supportRule(int level, boolean stuck) {
        String step = switch (level) {
            case 0 -> "support_level 0: if the learner makes a mistake or hesitates, give only a small hint. Do not give the full answer.";
            case 1 -> "support_level 1: the learner is stuck. State the rule they need in simple words, but do not give the answer yet.";
            case 2 -> "support_level 2: the learner is still stuck. Give the rule and one similar example, but let them build their own sentence.";
            default -> "support_level 3: the learner cannot do it alone. Give the correct answer plainly, explain it briefly, then ask them to repeat it or make one similar sentence.";
        };
        if (!stuck) {
            return "- " + step + "\n";
        }
        return "- " + step + "\n"
                + "- The learner says they do not understand. Use simpler English than before and fill reply_ru with a short explanation in Russian.\n";
    }

    /** В историю для модели кладём ответ учителя в сжатом виде — экономим контекст (4096 токенов). */
    private String compactAssistant(ChatMessage m) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (m.payloadJson() != null) {
            try {
                TeacherReply reply = mapper.readValue(m.payloadJson(), TeacherReply.class);
                compact.put("reply_en", reply.replyEn());
                if (reply.nextPrompt() != null) {
                    compact.put("next_prompt", reply.nextPrompt());
                }
                return mapper.writeValueAsString(compact);
            } catch (JacksonException ignored) {
                // повреждённый payload — используем видимый текст
            }
        }
        compact.put("reply_en", m.text());
        return mapper.writeValueAsString(compact);
    }
}
