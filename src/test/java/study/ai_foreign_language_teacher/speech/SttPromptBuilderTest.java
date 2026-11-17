package study.ai_foreign_language_teacher.speech;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.Role;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SttPromptBuilderTest {

    @Test
    void promptIncludesNameFromChat() {
        var msg = new ChatMessage(1, 1, Role.USER, "I have a pet named Vasia.", null, Instant.EPOCH);
        String prompt = SttPromptBuilder.forLesson(List.of(msg), "pets");
        assertTrue(prompt.contains("Vasia"));
        assertTrue(prompt.contains("pets"));
    }
}