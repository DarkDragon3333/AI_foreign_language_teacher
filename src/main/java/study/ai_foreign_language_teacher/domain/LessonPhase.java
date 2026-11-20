package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LessonPhase {
    WARMUP("warmup", "Warm-up"),
    INPUT("input", "Input"),
    PRACTICE("practice", "Practice"),
    FEEDBACK("feedback", "Feedback");

    private final String json;
    private final String label;

    LessonPhase(String json, String label) {
        this.json = json;
        this.label = label;
    }

    public String label() {
        return label;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static LessonPhase parse(String value) {
        if (value == null || value.isBlank()) {
            return WARMUP;
        }
        for (LessonPhase p : values()) {
            if (p.json.equalsIgnoreCase(value.trim()) || p.name().equalsIgnoreCase(value.trim())) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown lesson phase: " + value);
    }

    public boolean isBefore(LessonPhase other) {
        return ordinal() < other.ordinal();
    }
}