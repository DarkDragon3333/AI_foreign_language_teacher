package study.ai_foreign_language_teacher.speech;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SttNameHintsTest {

    @Test
    void fixesWasThatWhenVasiaIsInChat() {
        String out = SttNameHints.apply("Was that is a grey cat", Set.of("Vasia"));
        assertEquals("Vasia is a grey cat", out);
    }
}