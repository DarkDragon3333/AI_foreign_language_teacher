package study.ai_foreign_language_teacher.practice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListeningPracticeServiceTest {

    @Test
    void matchesExactAndShortPhraseInLongerAnswer() {
        ListeningPracticeService svc = new ListeningPracticeService(null, null, null, null);
        List<String> acceptable = List.of("Seven o'clock", "7");
        assertTrue(svc.matchesAcceptable(acceptable, "seven o'clock"));
        assertTrue(svc.matchesAcceptable(acceptable, "Tom wakes up at seven o'clock"));
        assertTrue(svc.matchesAcceptable(acceptable, "7"));
        assertFalse(svc.matchesAcceptable(acceptable, "Tom wakes up at eight"));
    }
}