package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

/** План guided-урока (режим structured), хранится в sessions.plan_state. */
public record StructuredPlanState(int targetMinutes, LessonPhase phase, Instant phaseStartedAt) {

    public static StructuredPlanState start(int targetMinutes, Instant now) {
        return new StructuredPlanState(targetMinutes, LessonPhase.WARMUP, now);
    }

    public StructuredPlanState withPhase(LessonPhase next, Instant now) {
        return new StructuredPlanState(targetMinutes, next, now);
    }
}