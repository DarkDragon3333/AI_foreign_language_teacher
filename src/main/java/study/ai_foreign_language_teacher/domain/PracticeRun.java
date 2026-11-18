package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

public record PracticeRun(long id, long userId, String kind, Cefr cefr, String stateJson,
                          Instant startedAt, Instant endedAt) {
}