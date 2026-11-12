package study.ai_foreign_language_teacher.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-схема контракта (раздел 6). Передаётся в Ollama как "format" — модель физически
 * не может выдать текст вне схемы. Все поля обязательны (маленьким моделям так проще);
 * пустые значения потом убирает ReplyParser.
 * reply_en стоит первым, чтобы его можно было стримить ученику, пока остальное генерируется.
 */
public final class ReplySchema {

    private ReplySchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> errorItem = object(props(
                "span", string(),
                "rule", string(),
                "hint", string()));

        Map<String, Object> correction = object(props(
                "original", string(),
                "corrected", string(),
                "errors", Map.of("type", "array", "items", errorItem)));

        Map<String, Object> explanation = object(props(
                "simple", string(),
                "example_ok", string(),
                "example_bad", string()));

        return object(props(
                "reply_en", string(),
                "reply_ru", string(),
                "correction", correction,
                "explanation", explanation,
                "next_prompt", string(),
                "cards_to_review", Map.of("type", "array", "items", string()),
                "mode", Map.of("type", "string", "enum", List.of("conversation", "explain", "correct", "drill")),
                "cefr", Map.of("type", "string", "enum", List.of("A1", "A2", "B1", "B2", "C1", "C2")),
                "support_level", Map.of("type", "integer", "minimum", 0, "maximum", 3)));
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        map.put("required", List.copyOf(properties.keySet()));
        return map;
    }
}
