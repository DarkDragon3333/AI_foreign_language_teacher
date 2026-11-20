package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.LessonPhase;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Таймбокс фаз guided-урока (15 или 25 минут). */
public final class StructuredLessonPhases {

    private static final Map<Integer, int[]> BUDGETS = Map.of(
            15, new int[]{3, 4, 5, 3},
            25, new int[]{5, 6, 9, 5});

    private StructuredLessonPhases() {
    }

    public static boolean isSupportedDuration(int minutes) {
        return BUDGETS.containsKey(minutes);
    }

    public static int normalizeDuration(int minutes) {
        if (minutes <= 15) {
            return 15;
        }
        return 25;
    }

    public static LessonPhase phaseAt(int targetMinutes, long elapsedSeconds) {
        int[] budgets = BUDGETS.getOrDefault(targetMinutes, BUDGETS.get(15));
        long elapsedMin = elapsedSeconds / 60;
        long boundary0 = budgets[0];
        long boundary1 = boundary0 + budgets[1];
        long boundary2 = boundary1 + budgets[2];
        if (elapsedMin < boundary0) {
            return LessonPhase.WARMUP;
        }
        if (elapsedMin < boundary1) {
            return LessonPhase.INPUT;
        }
        if (elapsedMin < boundary2) {
            return LessonPhase.PRACTICE;
        }
        return LessonPhase.FEEDBACK;
    }

    public static int minutesRemaining(int targetMinutes, Instant startedAt, Instant now) {
        if (startedAt == null || now == null) {
            return targetMinutes;
        }
        long elapsed = Duration.between(startedAt, now).getSeconds();
        long remaining = targetMinutes * 60L - elapsed;
        if (remaining <= 0) {
            return 0;
        }
        return (int) ((remaining + 59) / 60);
    }

    public static long elapsedSeconds(Instant startedAt, Instant now) {
        if (startedAt == null || now == null) {
            return 0;
        }
        return Math.max(0, Duration.between(startedAt, now).getSeconds());
    }

    public static Map<LessonPhase, Integer> budgetsFor(int targetMinutes) {
        int[] b = BUDGETS.getOrDefault(targetMinutes, BUDGETS.get(15));
        Map<LessonPhase, Integer> map = new EnumMap<>(LessonPhase.class);
        LessonPhase[] phases = LessonPhase.values();
        for (int i = 0; i < phases.length; i++) {
            map.put(phases[i], b[i]);
        }
        return map;
    }
}