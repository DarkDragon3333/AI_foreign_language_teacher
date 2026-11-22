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
        Instant endedAt,
        String model,
        SessionSummary summary,
        SessionPlanState planState) {

    public LessonSession(long id, long userId, LessonMode mode, String topic, Cefr cefr, int supportLevel,
                         Instant startedAt, Instant endedAt, String model, SessionSummary summary) {
        this(id, userId, mode, topic, cefr, supportLevel, startedAt, endedAt, model, summary, null);
    }

    public StructuredPlanState structuredPlan() {
        return planState instanceof StructuredPlanState s ? s : null;
    }

    public PlacementPlanState placementPlan() {
        return planState instanceof PlacementPlanState p ? p : null;
    }

    public LessonSession withSupportLevel(int level) {
        return new LessonSession(id, userId, mode, topic, cefr, level, startedAt, endedAt, model, summary, planState);
    }

    public LessonSession withPlanState(SessionPlanState state) {
        return new LessonSession(id, userId, mode, topic, cefr, supportLevel, startedAt, endedAt, model, summary, state);
    }
}
