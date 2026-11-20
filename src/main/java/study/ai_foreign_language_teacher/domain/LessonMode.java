package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LessonMode {
    CONVERSATION(0.7, "Free conversation on the topic. Keep the dialogue alive; correct briefly, then move on."),
    EXPLAIN(0.4, "Explain what the learner asks about: short rule, one good example, one bad example."),
    CORRECT(0.4, "The learner sends text to be corrected. Give the corrected version and explain each important error."),
    DRILL(0.4, "Run short practice drills: give one small task per turn, check the answer, then give the next task."),
    ROLEPLAY(0.7, "Stay in character for a role-play scene. Short natural lines; correct briefly and return to the scene."),
    STRUCTURED(0.55, "Follow the current lesson phase instructions in STRUCTURED LESSON. One short teacher turn per step.");

    private final double temperature;
    private final String instruction;

    LessonMode(double temperature, String instruction) {
        this.temperature = temperature;
        this.instruction = instruction;
    }

    /** Раздел 6: объяснения 0.3–0.5, свободный диалог 0.7. */
    public double temperature() {
        return temperature;
    }

    public String instruction() {
        return instruction;
    }

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static LessonMode parse(String value) {
        if (value == null || value.isBlank()) {
            return CONVERSATION;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown mode: " + value + " (expected conversation|explain|correct|drill|roleplay|structured)");
        }
    }
}
