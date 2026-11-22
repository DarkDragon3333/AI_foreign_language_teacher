package study.ai_foreign_language_teacher.domain;

/** Прогресс онбординга placement в plan_state. */
public record PlacementPlanState(
        PlacementStage stage,
        int testBlock,
        int testQuestion,
        int surveyQuestion,
        String assessedLevel,
        LearningPlan learningPlan) implements SessionPlanState {

    public static PlacementPlanState start() {
        return new PlacementPlanState(PlacementStage.TEST, 0, 0, 0, null, null);
    }

    public PlacementPlanState withStage(PlacementStage next) {
        return new PlacementPlanState(next, testBlock, testQuestion, surveyQuestion, assessedLevel, learningPlan);
    }

    public PlacementPlanState withTest(int block, int question) {
        return new PlacementPlanState(stage, block, question, surveyQuestion, assessedLevel, learningPlan);
    }

    public PlacementPlanState withSurvey(int question) {
        return new PlacementPlanState(stage, testBlock, testQuestion, question, assessedLevel, learningPlan);
    }

    public PlacementPlanState withAssessedLevel(String level) {
        return new PlacementPlanState(stage, testBlock, testQuestion, surveyQuestion, level, learningPlan);
    }

    public PlacementPlanState withLearningPlan(LearningPlan plan, String level) {
        return new PlacementPlanState(PlacementStage.READY, testBlock, testQuestion, surveyQuestion, level, plan);
    }
}