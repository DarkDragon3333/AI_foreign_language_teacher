package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GrammarExerciseType {
    GAP,
    CHOICE,
    REWRITE,
    FIND_ERROR;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static GrammarExerciseType parse(String value) {
        if (value == null || value.isBlank()) {
            return GAP;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown exercise type: " + value);
        }
    }
}