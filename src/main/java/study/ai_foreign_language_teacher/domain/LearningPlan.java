package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** План обучения после placement: дни недели, дневная цель и слоты на месяц. */
public record LearningPlan(
        @JsonProperty("study_weekdays") List<Integer> studyWeekdays,
        @JsonProperty("daily_goal_min") int dailyGoalMin,
        @JsonProperty("month_slots") List<MonthSlot> monthSlots,
        @JsonProperty("target_end_date") String targetEndDate) {

    public record MonthSlot(String date, @JsonProperty("activity_type") String activityType) {
    }
}