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
        assertEquals(1, r.cardsToReview().size());
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
}
