package study.ai_foreign_language_teacher.domain;

/** Оценка повторения, как в Anki: 1 снова, 2 трудно, 3 хорошо, 4 легко. */
public enum ReviewRating {
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4);

    private final int code;

    ReviewRating(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ReviewRating parse(int code) {
        for (ReviewRating rating : values()) {
            if (rating.code == code) {
                return rating;
            }
        }
        throw new IllegalArgumentException("Оценка должна быть от 1 до 4");
    }
}
