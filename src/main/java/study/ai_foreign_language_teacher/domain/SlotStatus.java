package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SlotStatus {
    PLANNED,
    DONE,
    SKIPPED,
    CANCELLED;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static SlotStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return PLANNED;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown slot status: " + value);
        }
    }
}