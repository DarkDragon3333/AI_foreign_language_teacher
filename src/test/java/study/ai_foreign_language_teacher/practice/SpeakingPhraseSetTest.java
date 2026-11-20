package study.ai_foreign_language_teacher.practice;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.Cefr;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SpeakingPhraseSetTest {

    @Test
    void padsWithFallbackWhenModelRepeats() {
        String one = "I would like a cup of tea, please.";
        List<String> out = SpeakingPhraseSet.prepare(List.of(one, one, one), Cefr.A2);
        assertEquals(SpeakingPhraseSet.SIZE, out.size());
        assertEquals(one, out.getFirst());
        for (int i = 1; i < out.size(); i++) {
            assertNotEquals(SpeechAligner.normalizeAnswer(out.get(i - 1)), SpeechAligner.normalizeAnswer(out.get(i)));
        }
    }

    @Test
    void fallbackAloneHasSixDistinct() {
        List<String> out = SpeakingPhraseSet.prepare(null, Cefr.B1);
        assertEquals(SpeakingPhraseSet.SIZE, out.size());
        for (int i = 0; i < out.size(); i++) {
            for (int j = i + 1; j < out.size(); j++) {
                assertNotEquals(
                        SpeechAligner.normalizeAnswer(out.get(i)),
                        SpeechAligner.normalizeAnswer(out.get(j)));
            }
        }
    }
}