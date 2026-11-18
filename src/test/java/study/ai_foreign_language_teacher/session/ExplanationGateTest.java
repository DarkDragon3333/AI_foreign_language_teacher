package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.TeacherReply.Explanation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplanationGateTest {

    @Test
    void inventedHeRideExampleIsNotAboutThisSentence() {
        String text = "I also like to ride my bike at the my city or in the forest near my house. It's help me tp relax after work";
        Explanation invented = new Explanation(
                "For habits (things you do often), we use Present Simple. In this tense, we add -s to verbs for he/she/it.",
                "He rides his bike every day.",
                "He ride his bike.",
                null,
                null);
        assertFalse(ExplanationGate.quotesLearner(text, invented, false));
        assertTrue(ExplanationGate.quotesLearner(text, invented, true));
    }
}
