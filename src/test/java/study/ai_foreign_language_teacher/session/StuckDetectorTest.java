package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StuckDetectorTest {

    @Test
    void recognisesRequestsForHelp() {
        assertTrue(StuckDetector.isStuck("Я не понимаю"));
        assertTrue(StuckDetector.isStuck("Sorry, I don’t understand this"));
        assertTrue(StuckDetector.isStuck("подскажи пожалуйста"));
    }

    @Test
    void ordinaryAnswersAreNotStuck() {
        assertFalse(StuckDetector.isStuck("I went to Turkey last summer"));
        assertFalse(StuckDetector.isStuck(""));
        assertFalse(StuckDetector.isStuck(null));
    }
}
