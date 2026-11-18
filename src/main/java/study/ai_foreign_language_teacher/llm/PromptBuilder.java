package study.ai_foreign_language_teacher.llm;

import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.RoleScenario;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.ScenarioGoal;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import study.ai_foreign_language_teacher.session.ScenarioService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Собирает сообщения для LLM: system prompt (раздел 12) + короткая история + текущая реплика. */
@Component
public class PromptBuilder {

    static final String LESSON_START = "[The lesson starts now. Greet the learner in one sentence and open the topic with a question.]";

    private final ObjectMapper mapper;
    private final ScenarioService scenarios;

    public PromptBuilder(ObjectMapper mapper, ScenarioService scenarios) {
        this.mapper = mapper;
        this.scenarios = scenarios;
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
        return build(profile, session, history, stuck, checker, weak, alreadyAsked, List.of());
    }

    /** @param cards одна-две проверенные заметки; модель объясняет по ним и не выдумывает правило */
    public List<LlmMessage> build(UserProfile profile, LessonSession session, List<ChatMessage> history, boolean stuck,
                                  List<LtMatch> checker, List<ErrorEvent> weak, List<String> alreadyAsked,
                                  List<RuleCard> cards) {
        return build(profile, session, history, stuck, checker, weak, alreadyAsked, cards, session.id());
    }

    public List<LlmMessage> build(UserProfile profile, LessonSession session, List<ChatMessage> history, boolean stuck,
                                  List<LtMatch> checker, List<ErrorEvent> weak, List<String> alreadyAsked,
                                  List<RuleCard> cards, long sessionId) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt(profile, session, stuck) + rolePlay(session, sessionId)
                + checkerReport(checker) + weakPoints(weak) + alreadyAsked(alreadyAsked) + ruleCards(cards)));
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
        if (session.mode() == LessonMode.ROLEPLAY) {
            topic = scenarios.promptContext(session).map(RoleScenario::topicLine).orElse(topic);
        }
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
                - answer_en is YOUR OWN short reaction to what the learner said (1-2 sentences): answer their question, comment on it, or encourage them. It is not a corrected copy of the learner's message: never repeat or rephrase the learner's sentence in answer_en.
                - answer_ru and follow_up_ru are Russian. Leave both empty unless the level rules or the support rules ask for Russian.
                - If the learner asks you something, answer it in answer_en first.
                - If the learner's last message has a real mistake, fill "correction". First "errors": one item per mistake (fragment = the wrong words copied exactly from the learner's message, rule = short rule name, tip = one short tip). Then "fixed_text": the learner's whole message with only those mistakes fixed. Never shorten, split or rephrase a correct sentence: style is not a mistake. If there is nothing to fix, leave errors empty and fixed_text empty.
                - Fill "explanation" only when you explain a rule or the learner asks why: brief_rule = the rule in one or two short sentences, example_correct = one correct example, example_wrong = the learner's own wrong words, not a new sentence about another person. Otherwise leave its fields empty.
                - Only correct what you are sure about. Do not invent grammar rules.
                - follow_up_question is exactly one NEW question or task that moves the lesson forward. Never write the same sentence in answer_en and follow_up_question, and never ask a question you have already asked.
                - End answer_en with . ! or ? only — no comma at the end before follow_up_question. Do not start follow_up_question with a comma.
                - When Russian is required, translate the whole message the learner will read. answer_ru translates every sentence of answer_en and nothing else. follow_up_ru translates all of follow_up_question and nothing else. Do not skip a sentence and do not add anything that is not in the English. If follow_up_question is empty, follow_up_ru is empty.
                - Plain text only: no markdown, no asterisks.
                """.formatted(session.cefr(), session.cefr(), session.cefr().teachingGuidance(),
                session.mode().json(), session.mode().instruction(), topic));

        sb.append("""

                EXAMPLE
                Learner: "I like play football with my friends."
                Good: answer_en = "That sounds fun! Football is a great way to see friends.", follow_up_question = "How often do you play?"
                When Russian is required, same turn: answer_ru = "Звучит здорово! Футбол — отличный способ видеться с друзьями.", follow_up_ru = "Как часто ты играешь?"
                Bad: answer_en = "I like to play football with my friends." (it only repeats the learner)
                Bad: answer_ru that stops before the question, or follow_up_ru left empty while follow_up_question is filled.
                """);
        sb.append("\nSUPPORT\n").append(supportRule(session.supportLevel(), stuck));
        sb.append("\nAnswer with one JSON object only, following the given schema.\n");
        return sb.toString();
    }

    String rolePlay(LessonSession session, long sessionId) {
        if (session.mode() != LessonMode.ROLEPLAY) {
            return "";
        }
        return scenarios.promptContext(session).map(s -> rolePlayBlock(s, scenarios.completedGoals(sessionId, session))).orElse("");
    }

    static String rolePlayBlock(RoleScenario scenario, Set<String> done) {
        StringBuilder sb = new StringBuilder("""

                ROLE-PLAY
                Setting: """);
        sb.append(scenario.setting()).append('\n');
        sb.append("You are: ").append(scenario.teacherRole()).append(". The learner is: ")
                .append(scenario.learnerRole()).append(".\n");
        sb.append("""
                Stay in character. Keep lines short and natural for the learner's CEFR level.
                Correct mistakes briefly, then return to the scene. Do not read out the checklist of goals.
                Nudge the learner toward goals they have not tried yet.
                """);
        sb.append("\nGoals (for your planning, not to recite):\n");
        for (ScenarioGoal g : scenario.goals()) {
            sb.append(done.contains(g.id()) ? "[done] " : "[ ] ");
            sb.append(g.textEn()).append(" — ").append(g.textRu()).append('\n');
        }
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
                    Add a correction only for a clear grammar or word-choice mistake that you can quote exactly in errors.fragment.
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
            sb.append("Possible typos. Fix a word in \"fixed_text\" only if it is really misspelled. "
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

    /** Заметки, написанные заранее. Модель не имеет права подменить их своим правилом. */
    static String ruleCards(List<RuleCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""

                RULE CARDS
                These notes were written and checked in advance. If you explain a mistake or the learner asked about a rule,
                follow the matching note and do not invent a different rule. Copy the idea of its rule and its two examples.
                If none of the notes fit, leave the explanation fields empty.
                """);
        for (RuleCard card : cards) {
            sb.append("- ").append(card.title()).append(" (").append(card.slug()).append(")\n");
            sb.append("  Rule: ").append(card.rule()).append('\n');
            sb.append("  Correct: ").append(card.exampleOk()).append('\n');
            sb.append("  Wrong: ").append(card.exampleBad()).append('\n');
            sb.append("  Russian speakers often: ").append(card.l1Trap()).append('\n');
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
                + "- The learner says they do not understand. Use simpler English than before and fill answer_ru and follow_up_ru so every sentence of your English, including the question, is translated into Russian.\n";
    }

    /** В историю для модели кладём ответ учителя в сжатом виде — экономим контекст (4096 токенов). */
    private String compactAssistant(ChatMessage m) {
        Map<String, Object> compact = new LinkedHashMap<>();
        if (m.payloadJson() != null) {
            try {
                TeacherReply reply = mapper.readValue(m.payloadJson(), TeacherReply.class);
                compact.put("answer_en", reply.replyEn());
                if (reply.nextPrompt() != null) {
                    compact.put("follow_up_question", reply.nextPrompt());
                }
                return mapper.writeValueAsString(compact);
            } catch (JacksonException ignored) {
                // повреждённый payload — используем видимый текст
            }
        }
        compact.put("answer_en", m.text());
        return mapper.writeValueAsString(compact);
    }
}
