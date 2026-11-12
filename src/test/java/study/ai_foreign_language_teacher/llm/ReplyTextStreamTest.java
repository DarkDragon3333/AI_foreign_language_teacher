package study.ai_foreign_language_teacher.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplyTextStreamTest {

    private static String feed(String json, int chunkSize) {
        StringBuilder out = new StringBuilder();
        ReplyTextStream stream = new ReplyTextStream("reply_en", out::append);
        for (int i = 0; i < json.length(); i += chunkSize) {
            stream.accept(json.substring(i, Math.min(json.length(), i + chunkSize)));
        }
        return out.toString();
    }

    @Test
    void extractsValueWhateverTheChunking() {
        String json = "{ \"reply_en\" : \"Hi! We say \\\"went\\\".\\nGood \\u0441\\u043b\\u043e\\u0432\\u043e \\\\ ok\", \"reply_ru\": \"нет\" }";
        String expected = "Hi! We say \"went\".\nGood слово \\ ok";
        for (int size = 1; size <= json.length(); size++) {
            assertEquals(expected, feed(json, size), "chunk size " + size);
        }
    }

    @Test
    void ignoresOtherFieldsAndStopsAfterValue() {
        assertEquals("yes", feed("{\"mode\":\"drill\",\"reply_en\":\"yes\",\"next_prompt\":\"reply_en\"}", 3));
    }

    @Test
    void emitsNothingWhenFieldIsMissingOrNotAString() {
        assertEquals("", feed("{\"reply_ru\":\"текст\"}", 2));
        assertEquals("", feed("{\"reply_en\":null,\"x\":\"y\"}", 2));
    }
}
