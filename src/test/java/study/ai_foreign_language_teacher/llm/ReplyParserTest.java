package study.ai_foreign_language_teacher.llm;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplyParserTest {

    private final ReplyParser parser = new ReplyParser(JsonMapper.builder().build());

    private TeacherReply parse(String raw) {
        return parser.parse(raw, Cefr.B1, LessonMode.CONVERSATION, 2);
    }

    @Test
    void sessionValuesOverrideWhatTheModelClaims() {
        TeacherReply r = parse("{\"reply_en\":\" Hello \",\"cefr\":\"C2\",\"mode\":\"drill\",\"support_level\":0}");
        assertEquals("Hello", r.replyEn());
        assertEquals("B1", r.cefr());
        assertEquals("conversation", r.mode());
        assertEquals(Integer.valueOf(2), r.supportLevel());
    }

    @Test
    void emptyBlocksAreDropped() {
        TeacherReply r = parse("""
                {"reply_en":"Good!","reply_ru":"  ",
                 "correction":{"original":"I am fine","corrected":"I am fine","errors":[]},
                 "explanation":{"simple":"","example_ok":"","example_bad":""},
                 "next_prompt":"","cards_to_review":["", "a", "a"]}""");
        assertNull(r.replyRu());
        assertNull(r.correction());
        assertNull(r.explanation());
        assertNull(r.nextPrompt());
        assertEquals(0, r.cardsToReview().size()); // карточки модель больше не выбирает
        assertEquals("Good!", r.displayText());
    }

    @Test
    void realCorrectionIsKept() {
        TeacherReply r = parse("""
                {"reply_en":"Nice.","correction":{"original":"I go yesterday","corrected":"I went yesterday",
                 "errors":[{"span":"go","rule":"Past Simple","hint":"go -> went"},{"span":"","rule":"","hint":""}]},
                 "next_prompt":"And then?"}""");
        assertNotNull(r.correction());
        assertEquals(1, r.correction().errors().size());
        assertEquals("Nice. And then?", r.displayText());
    }

    @Test
    void jsonWrappedInMarkdownFenceIsAccepted() {
        assertEquals("Hi", parse("```json\n{\"reply_en\":\"Hi\"}\n```").replyEn());
    }

    @Test
    void brokenOutputFallsBackInsteadOfFailing() {
        assertEquals(ReplyParser.FALLBACK_TEXT, parse("{\"reply_en\": \"cut off").replyEn());
        assertEquals(ReplyParser.FALLBACK_TEXT, parse("").replyEn());
        assertEquals(ReplyParser.FALLBACK_TEXT, parse("{\"reply_ru\":\"только русский\"}").replyEn());
        assertEquals("Plain text answer.", parse(" Plain text answer. ").replyEn());
    }

    @Test
    void truncatedJsonKeepsTheTextTheLearnerAlreadySaw() {
        TeacherReply r = parser.parse("{\"reply_en\": \"Well done! Now", "Well done! Now", Cefr.B1, LessonMode.CONVERSATION, 0);
        assertEquals("Well done! Now", r.replyEn());
    }

    @Test
    void questionRepeatedInBothFieldsIsShownOnce() {
        TeacherReply r = parse("{\"reply_en\":\"Great! What do you like about Greece?\",\"next_prompt\":\"What do you like about Greece?\"}");
        assertNull(r.nextPrompt());
        assertEquals("Great! What do you like about Greece?", r.displayText());
    }

    @Test
    void replyGivenOnlyAsQuestionIsNotAFailure() {
        TeacherReply r = parse("{\"reply_en\":\"\",\"next_prompt\":\"Hello! What is your job?\"}");
        assertEquals("Hello! What is your job?", r.replyEn());
        assertNull(r.nextPrompt());
    }

    @Test
    void newWireFormatIsMappedToTheInterfaceContract() {
        // порядок полей — как его выдаёт модель: ответ, ошибки, исправленная фраза, правило, вопрос
        TeacherReply r = parse("""
                {"answer_en":"Nice trip!","answer_ru":"Хорошая поездка!",
                 "correction":{"errors":[{"fragment":"go","rule":"Past Simple","tip":"go -> went"}],"fixed_text":"I **went** to Turkey last summer."},
                 "explanation":{"brief_rule":"Use *went* for the past.","example_correct":"I went home.","example_wrong":"I go home yesterday."},
                 "follow_up_question":"What did you see there?"}""");
        assertEquals("Nice trip!", r.replyEn());
        assertEquals("Хорошая поездка!", r.replyRu());
        assertEquals("What did you see there?", r.nextPrompt());
        assertEquals("I went to Turkey last summer.", r.correction().corrected());
        assertEquals("go", r.correction().errors().get(0).span());
        assertEquals("go -> went", r.correction().errors().get(0).hint());
        assertEquals("Use went for the past.", r.explanation().simple());
    }

    @Test
    void emptyCorrectionInTheNewFormatIsDropped() {
        TeacherReply r = parse("{\"answer_en\":\"Good!\",\"correction\":{\"errors\":[],\"fixed_text\":\"\"},\"follow_up_question\":\"And you?\"}");
        assertNull(r.correction());
    }

    @Test
    void schemaFieldsAreInAlphabeticalOrderAtEveryLevel() {
        // так порядок генерации не зависит от того, сортирует ли движок Ollama поля схемы
        assertSorted(ReplySchema.schema());
    }

    @SuppressWarnings("unchecked")
    private static void assertSorted(java.util.Map<String, Object> schema) {
        Object properties = schema.get("properties");
        if (properties instanceof java.util.Map<?, ?> map) {
            java.util.List<String> keys = new java.util.ArrayList<>((java.util.Collection<String>) map.keySet());
            java.util.List<String> sorted = new java.util.ArrayList<>(keys);
            java.util.Collections.sort(sorted);
            assertEquals(sorted, keys);
            for (Object child : map.values()) {
                assertSorted((java.util.Map<String, Object>) child);
            }
        }
        if (schema.get("items") instanceof java.util.Map<?, ?> items) {
            assertSorted((java.util.Map<String, Object>) items);
        }
    }
}
