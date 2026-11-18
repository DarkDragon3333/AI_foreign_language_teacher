package study.ai_foreign_language_teacher.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GrammarDrillSchema {

    private GrammarDrillSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> item = object(props(
                "type", string(),
                "prompt", string(),
                "choices", Map.of("type", "array", "items", string()),
                "answer", string(),
                "rule", string()));
        return object(props("exercises", Map.of("type", "array", "items", item)));
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