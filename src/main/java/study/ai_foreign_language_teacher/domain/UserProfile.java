package study.ai_foreign_language_teacher.domain;

import java.time.LocalDate;
import java.util.List;

public record UserProfile(
        long id,
        String displayName,
        String nativeLang,
        String targetLang,
        Cefr cefr,
        boolean voiceEnabled,
        int dailyGoalMin,
        List<Integer> studyWeekdays,
        LocalDate targetEndDate) {

    public static final int DEFAULT_DAILY_GOAL_MIN = 10;
    public static final List<Integer> DEFAULT_STUDY_WEEKDAYS = List.of(1, 2, 3, 4, 5);
}
