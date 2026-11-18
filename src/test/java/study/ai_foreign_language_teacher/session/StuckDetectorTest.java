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

    @Test
    void helpMeInsideASentenceIsNotARequest() {
        // реальный случай: «It's help me tp relax» приняли за просьбу о помощи и не проверили фразу
        assertFalse(StuckDetector.isStuck(
                "I also like to ride my bike at the my city or in the forest near my house. It's help me tp relax after work"));
        assertFalse(StuckDetector.isStuck("Bikes help me relax after work"));
        assertTrue(StuckDetector.isStuck("help me"));
        assertTrue(StuckDetector.isStuck("Can you help me?"));
        assertTrue(StuckDetector.isStuck("please help me with this"));
        assertTrue(StuckDetector.isStuck("Sorry, help me"));
    }
}
