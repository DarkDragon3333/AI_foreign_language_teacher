package study.ai_foreign_language_teacher.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-схема ответа модели в режиме placement (базовый контракт + placement_meta). */
public final class PlacementReplySchema {

    private PlacementReplySchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> base = ReplySchema.schema();
        Map<String, Object> slotItem = object(Map.of(
                "date", string(),
                "activity_type", string()));

        Map<String, Object> learningPlan = object(props(
                "study_weekdays", Map.of("type", "array", "items", Map.of("type", "integer")),
                "daily_goal_min", Map.of("type", "integer"),
                "month_slots", Map.of("type", "array", "items", slotItem)));

        Map<String, Object> placementMeta = object(props(
                "assessed_level", Map.of(
                        "type", "string",
                        "enum", List.of("A1", "A2", "B1", "B2", "C1", "C2")),
                "learning_plan", learningPlan));

        Map<String, Object> props = new LinkedHashMap<>(castProps(base.get("properties")));
        props.put("reading_passage", string());
        props.put("placement_meta", placementMeta);
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("properties", props);
        @SuppressWarnings("unchecked")
        List<String> required = new java.util.ArrayList<>((List<String>) base.get("required"));
        required.add("reading_passage");
        required.add("placement_meta");
        out.put("required", List.copyOf(required));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castProps(Object value) {
        return (Map<String, Object>) value;
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