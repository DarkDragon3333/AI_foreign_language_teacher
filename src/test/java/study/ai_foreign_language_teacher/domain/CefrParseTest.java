package study.ai_foreign_language_teacher.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CefrParseTest {

    @Test
    void extractsFirstCodeFromFreeText() {
        assertEquals(Cefr.B1, Cefr.parse("B1-B2 (Intermediate)"));
        assertEquals(Cefr.A2, Cefr.parse("around a2 level"));
    }

    @Test
    void rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Cefr.parse("Intermediate only"));
    }
}