package study.ai_foreign_language_teacher.export;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.RuleCard;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnkiCsvTest {

    @Test
    void escapesCommasAndQuotes() {
        assertEquals("\"a,b\"", AnkiCsv.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AnkiCsv.escape("say \"hi\""));
        assertEquals("plain", AnkiCsv.escape("plain"));
    }

    @Test
    void exportHasHeaderAndBom() {
        RuleCard card = new RuleCard("test_rule", "A1", "A2", "Test", "Тест", "Rule", "Правило",
                "I am fine.", "I fine.", "Ловушка", List.of(), List.of());
        byte[] raw = AnkiCsv.export(List.of(card));
        assertEquals((byte) 0xEF, raw[0]);
        String text = new String(raw, StandardCharsets.UTF_8);
        assertTrue(text.contains("Front,Back,Tags"));
        assertTrue(text.contains("test-rule"));
    }
}