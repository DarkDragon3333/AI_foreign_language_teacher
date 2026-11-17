package study.ai_foreign_language_teacher.domain;

import java.util.List;

/** Одна цель в ролевом сценарии: ученик выполняет её репликой на английском. */
public record ScenarioGoal(String id, String textRu, String textEn, List<String> hints) {

    public ScenarioGoal {
        hints = hints == null ? List.of() : List.copyOf(hints);
    }
}