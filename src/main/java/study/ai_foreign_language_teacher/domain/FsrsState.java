package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

/**
 * Карточка повторения одного ученика.
 * state: new — ещё не оценивали; review — в обычном интервале; relearning — после провала.
 */
public record FsrsState(
        long userId,
        String slug,
        String state,
        Instant due,
        double stability,
        double difficulty,
        int reps,
        int lapses,
        Instant lastReview) {

    public static FsrsState fresh(long userId, String slug, Instant due) {
        return new FsrsState(userId, slug, "new", due, 0, 0, 0, 0, null);
    }
}
