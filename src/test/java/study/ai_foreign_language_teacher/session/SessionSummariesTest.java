package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionSummariesTest {

    @Test
    void durationMinutesRoundsUp() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        Instant end = start.plusSeconds(61);
        assertEquals(2, SessionSummaries.durationMinutes(start, end));
    }

    @Test
    void durationMinutesZeroWhenSameInstant() {
        Instant t = Instant.now();
        assertEquals(0, SessionSummaries.durationMinutes(t, t));
    }
}