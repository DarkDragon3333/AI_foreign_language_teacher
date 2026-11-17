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

    @Test
    void dontKnowIsStuckOnlyAsTheWholeMessage() {
        // реальный случай: обычный ответ был принят за просьбу о помощи
        assertFalse(StuckDetector.isStuck("I don't know what I want to do"));
        assertFalse(StuckDetector.isStuck("Я не знаю, кем хочу быть"));
        assertTrue(StuckDetector.isStuck("I don't know."));
        assertTrue(StuckDetector.isStuck("не знаю"));
        assertTrue(StuckDetector.isStuck("Sorry, I don't know"));
    }
}
