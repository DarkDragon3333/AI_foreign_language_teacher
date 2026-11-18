package study.ai_foreign_language_teacher.nlp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageToolClientTest {

    @Test
    void styleAdviceIsNotALearnerMistake() {
        assertFalse(LanguageToolClient.forLearners("style"));
        assertTrue(LanguageToolClient.forLearners("grammar"));
        assertTrue(LanguageToolClient.forLearners(""));
    }
}
