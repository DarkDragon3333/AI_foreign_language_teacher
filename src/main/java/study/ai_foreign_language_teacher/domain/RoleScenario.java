package study.ai_foreign_language_teacher.domain;

import java.util.List;

/** Ролевая ситуация: кафе, собеседование и т.п. Тема урока — slug сценария. */
public record RoleScenario(
        String slug,
        String title,
        String titleRu,
        String setting,
        String teacherRole,
        String learnerRole,
        String cefrMin,
        String cefrMax,
        List<ScenarioGoal> goals) {

    public RoleScenario {
        goals = goals == null ? List.of() : List.copyOf(goals);
    }

    public String topicLine() {
        return title + " — " + setting + " You play " + teacherRole + "; the learner plays " + learnerRole + ".";
    }
}