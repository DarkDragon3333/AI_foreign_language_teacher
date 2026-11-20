package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.LessonPhase;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredLessonPhasesTest {

    @Test
    void phases15Min() {
        assertEquals(LessonPhase.WARMUP, StructuredLessonPhases.phaseAt(15, 0));
        assertEquals(LessonPhase.WARMUP, StructuredLessonPhases.phaseAt(15, 2 * 60));
        assertEquals(LessonPhase.INPUT, StructuredLessonPhases.phaseAt(15, 3 * 60));
        assertEquals(LessonPhase.PRACTICE, StructuredLessonPhases.phaseAt(15, 7 * 60));
        assertEquals(LessonPhase.FEEDBACK, StructuredLessonPhases.phaseAt(15, 12 * 60));
    }

    @Test
    void phases25Min() {
        assertEquals(LessonPhase.WARMUP, StructuredLessonPhases.phaseAt(25, 4 * 60));
        assertEquals(LessonPhase.INPUT, StructuredLessonPhases.phaseAt(25, 5 * 60));
        assertEquals(LessonPhase.PRACTICE, StructuredLessonPhases.phaseAt(25, 11 * 60));
        assertEquals(LessonPhase.FEEDBACK, StructuredLessonPhases.phaseAt(25, 20 * 60));
    }

    @Test
    void minutesRemaining() {
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        assertEquals(10, StructuredLessonPhases.minutesRemaining(15, start, start.plusSeconds(5 * 60)));
        assertEquals(0, StructuredLessonPhases.minutesRemaining(15, start, start.plusSeconds(20 * 60)));
    }
}