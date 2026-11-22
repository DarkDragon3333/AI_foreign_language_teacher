package study.ai_foreign_language_teacher.domain;

/** Состояние в sessions.plan_state: guided-урок или онбординг placement. */
public sealed interface SessionPlanState permits StructuredPlanState, PlacementPlanState {
}