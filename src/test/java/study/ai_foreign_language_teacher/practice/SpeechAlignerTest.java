package study.ai_foreign_language_teacher.practice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechAlignerTest {

    @Test
    void spotsWrongWord() {
        var mismatches = SpeechAligner.compare("I think it is fine", "I sink it is fine");
        assertTrue(mismatches.stream().anyMatch(m -> "think".equals(m.expected()) && "sink".equals(m.heard())));
    }

    @Test
    void exactPhraseHasNoMismatches() {
        assertEquals(0, SpeechAligner.compare("Hello there", "Hello there").size());
    }
}