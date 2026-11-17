package study.ai_foreign_language_teacher.memory.rules;

import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.domain.ReviewRating;

import java.time.Duration;
import java.time.Instant;

/**
 * FSRS-5, параметры по умолчанию из open-spaced-repetition (те же, что в Anki).
 * Считает не «насколько ученик умный», а когда снова показать правило:
 * «Снова» — в тот же день, «Трудно» — около суток, «Хорошо» — около трёх дней, «Легко» — около двух недель.
 */
public final class FsrsScheduler {

    static final double[] W = {
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575,
            0.1192, 1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621
    };
    private static final double DECAY = -0.5;
    /** Подобран так, чтобы при желаемой вероятности 0.9 интервал совпадал со стабильностью. */
    private static final double FACTOR = Math.pow(0.9, 1.0 / DECAY) - 1.0;
    private static final double RETENTION = 0.9;

    private FsrsScheduler() {
    }

    public record Outcome(FsrsState state, long intervalDays) {
    }

    public static Outcome review(FsrsState card, ReviewRating rating, Instant now) {
        double stability;
        double difficulty;
        String state;
        int lapses = card.lapses();
        if (card.lastReview() == null || card.stability() <= 0 || "new".equals(card.state())) {
            stability = Math.max(W[rating.code() - 1], 0.1);
            difficulty = initDifficulty(rating);
            state = rating == ReviewRating.AGAIN ? "relearning" : "review";
            if (rating == ReviewRating.AGAIN) {
                lapses++;
            }
        } else {
            double elapsed = Duration.between(card.lastReview(), now).toMinutes() / 1440.0;
            double r = retrievability(Math.max(0, elapsed), card.stability());
            difficulty = nextDifficulty(card.difficulty(), rating);
            if (rating == ReviewRating.AGAIN) {
                stability = Math.min(card.stability(), forgetStability(card.difficulty(), card.stability(), r));
                state = "relearning";
                lapses++;
            } else if (elapsed < 1) {
                stability = card.stability() * Math.exp(W[17] * (rating.code() - 3 + W[18]));
                state = "review";
            } else {
                stability = recallStability(card.difficulty(), card.stability(), r, rating);
                state = "review";
            }
        }
        stability = Math.max(0.1, stability);
        double days = intervalDays(stability);
        Instant due;
        long shownDays;
        if (days < 1) {
            long minutes = Math.max(10, Math.round(days * 24 * 60));
            due = now.plus(Duration.ofMinutes(minutes));
            shownDays = 0;
        } else {
            shownDays = Math.max(1, Math.round(days));
            due = now.plus(Duration.ofDays(shownDays));
        }
        FsrsState next = new FsrsState(card.userId(), card.slug(), state, due, stability, difficulty,
                card.reps() + 1, lapses, now);
        return new Outcome(next, shownDays);
    }

    static double intervalDays(double stability) {
        return (stability / FACTOR) * (Math.pow(RETENTION, 1.0 / DECAY) - 1.0);
    }

    private static double retrievability(double elapsedDays, double stability) {
        return Math.pow(1 + FACTOR * elapsedDays / stability, DECAY);
    }

    private static double initDifficulty(ReviewRating rating) {
        return clamp(W[4] - Math.exp(W[5] * (rating.code() - 1)) + 1);
    }

    private static double nextDifficulty(double difficulty, ReviewRating rating) {
        double delta = -W[6] * (rating.code() - 3);
        double damped = difficulty + (10 - difficulty) * delta / 9.0;
        double easy = initDifficulty(ReviewRating.EASY);
        return clamp(W[7] * easy + (1 - W[7]) * damped);
    }

    private static double recallStability(double difficulty, double stability, double r, ReviewRating rating) {
        double hard = rating == ReviewRating.HARD ? W[15] : 1;
        double easy = rating == ReviewRating.EASY ? W[16] : 1;
        return stability * (1 + Math.exp(W[8]) * (11 - difficulty) * Math.pow(stability, -W[9])
                * (Math.exp((1 - r) * W[10]) - 1) * hard * easy);
    }

    private static double forgetStability(double difficulty, double stability, double r) {
        return W[11] * Math.pow(difficulty, -W[12]) * (Math.pow(stability + 1, W[13]) - 1)
                * Math.exp((1 - r) * W[14]);
    }

    private static double clamp(double difficulty) {
        return Math.min(10, Math.max(1, difficulty));
    }
}
