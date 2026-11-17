package study.ai_foreign_language_teacher.speech;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeakableSentencesTest {

    @Test
    void splitsStreamingTextBySentence() {
        List<String> out = new ArrayList<>();
        SpeakableSentences split = new SpeakableSentences(out::add);
        split.accept("Hello there. How ");
        split.accept("are you?");
        split.flush();
        assertEquals(List.of("Hello there.", "How are you?"), out);
    }
}