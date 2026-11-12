package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

public record LessonSession(
        long id,
        long userId,
        LessonMode mode,
        String topic,
        Cefr cefr,
        int supportLevel,
        Instant startedAt,
        Instant endedAt) {
}
