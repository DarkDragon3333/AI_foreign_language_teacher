package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ActivityType {
    REVIEW,
    GRAMMAR,
    SPEAKING,
    LISTENING,
    CONVERSATION;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ActivityType parse(String value) {
        if (value == null || value.isBlank()) {
            return CONVERSATION;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown activity: " + value);
        }
    }
}