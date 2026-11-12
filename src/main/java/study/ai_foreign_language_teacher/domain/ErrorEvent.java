package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

/** Строка журнала ошибок: одно правило и сколько раз ученик на нём ошибся. */
public record ErrorEvent(
        String ruleId,
        String source,
        String category,
        String title,
        String sampleSpan,
        int count,
        Instant lastSeen) {
}
