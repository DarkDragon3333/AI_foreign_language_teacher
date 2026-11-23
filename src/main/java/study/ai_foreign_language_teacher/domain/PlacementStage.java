package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PlacementStage {
    TEST,
    LADDER,
    OFFER,
    REFINE,
    SURVEY,
    PLAN,
    READY;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static PlacementStage parse(String value) {
        if (value == null || value.isBlank()) {
            return TEST;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TEST;
        }
    }
}