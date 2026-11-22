package study.ai_foreign_language_teacher.domain;

import java.util.List;

/** Допустимые значения дневной цели в минутах (профиль, placement, UI). */
public final class DailyGoalMinutes {

    public static final List<Integer> CHOICES = List.of(5, 10, 15, 20, 25, 30, 45, 60);

    private DailyGoalMinutes() {
    }

    public static int normalize(int raw) {
        return CHOICES.contains(raw) ? raw : 15;
    }

    public static void requireValid(Integer goal) {
        if (goal != null && !CHOICES.contains(goal)) {
            throw new IllegalArgumentException("Daily goal must be one of: " + CHOICES);
        }
    }
}