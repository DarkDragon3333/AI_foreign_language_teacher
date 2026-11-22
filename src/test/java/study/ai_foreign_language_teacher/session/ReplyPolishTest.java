package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.TeacherReply;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyPolishTest {

    private static TeacherReply reply(String replyEn, String nextPrompt) {
        return new TeacherReply(replyEn, "перевод", null, null, nextPrompt, List.of(), "conversation", "A1", 0, null);
    }

    @Test
    void echoOfLearnerSentenceIsReplacedByTheQuestion() {
        // реальные случаи со скриншотов
        TeacherReply r = ReplyPolish.dropEcho(reply("I like the end of work.", "What is your job title?"), "I like the end work day");
        assertEquals("What is your job title?", r.replyEn());
        assertNull(r.nextPrompt());
        assertNull(r.replyRu());
        assertEquals("Do you like working in an office?",
                ReplyPolish.dropEcho(reply("Hi! What's up?", "Do you like working in an office?"), "Hi! What's up?").replyEn());
    }

    @Test
    void realReactionIsKept() {
        TeacherReply r = ReplyPolish.dropEcho(reply("Job title is the name of your job.", "What is your job title?"), "What is mean \"job title\"?");
        assertEquals("Job title is the name of your job.", r.replyEn());
        assertEquals("What is your job title?", r.nextPrompt());
    }

    @Test
    void repeatedQuestionIsStale() {
        List<String> recent = List.of("Hello! What is your job?", "Job title is the name of your job. What is your job title?");
        assertTrue(ReplyPolish.isStale(reply("Good.", "What is your job title?"), "It is developer", recent));
        assertTrue(ReplyPolish.isStale(reply("What is your job?", null), "I like it", recent));
        assertFalse(ReplyPolish.isStale(reply("Good.", "What do you do after work?"), "It is developer", recent));
    }

    @Test
    void bareEchoWithoutQuestionIsStale() {
        assertTrue(ReplyPolish.isStale(reply("Hi! What's up?", null), "Hi! What's up?", List.of()));
    }
}
