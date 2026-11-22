package study.ai_foreign_language_teacher.llm;

import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.DailyGoalMinutes;
import study.ai_foreign_language_teacher.domain.LearningPlan;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Component
public class PlacementMetaParser {

    private final ObjectMapper mapper;

    public PlacementMetaParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Parsed(String assessedLevel, LearningPlan learningPlan) {
    }

    public Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode meta = root.path("placement_meta");
            if (meta.isMissingNode() || meta.isNull()) {
                return null;
            }
            String level = text(meta, "assessed_level");
            LearningPlan plan = null;
            JsonNode planNode = meta.path("learning_plan");
            if (!planNode.isMissingNode() && !planNode.isNull()) {
                plan = readPlan(planNode);
            }
            if ((level == null || level.isBlank()) && plan == null) {
                return null;
            }
            return new Parsed(level, plan);
        } catch (JacksonException e) {
            return null;
        }
    }

    private LearningPlan readPlan(JsonNode planNode) {
        List<Integer> days = new ArrayList<>();
        for (JsonNode d : planNode.path("study_weekdays")) {
            if (d.isInt()) {
                days.add(d.intValue());
            }
        }
        int goal = DailyGoalMinutes.normalize(planNode.path("daily_goal_min").asInt(15));
        List<LearningPlan.MonthSlot> slots = new ArrayList<>();
        for (JsonNode s : planNode.path("month_slots")) {
            String date = text(s, "date");
            String type = text(s, "activity_type");
            if (date != null && type != null) {
                slots.add(new LearningPlan.MonthSlot(date, type));
            }
        }
        String targetEnd = text(planNode, "target_end_date");
        if (days.isEmpty() && slots.isEmpty() && goal <= 0 && targetEnd == null) {
            return null;
        }
        return new LearningPlan(days, goal, slots, targetEnd);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString(null);
        return s == null || s.isBlank() ? null : s.strip();
    }
}