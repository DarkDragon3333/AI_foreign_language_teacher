package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Прогресс онбординга placement в plan_state. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlacementPlanState(
        PlacementStage stage,
        int testBlock,
        int testQuestion,
        int surveyQuestion,
        String assessedLevel,
        LearningPlan learningPlan,
        String band,
        List<LadderHit> hits,
        String missBand,
        String boundary,
        String preliminaryLevel,
        String currentItemId,
        Integer refineAsked,
        Integer refineCorrect) implements SessionPlanState {

    @JsonCreator
    public PlacementPlanState {
        if (stage == null) {
            stage = PlacementStage.LADDER;
        }
        if (band == null || band.isBlank()) {
            band = "B1";
        }
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (refineAsked == null) {
            refineAsked = 0;
        }
        if (refineCorrect == null) {
            refineCorrect = 0;
        }
    }

    /** Конструктор без полей лесенки: старые вызовы и JSON без новых ключей. */
    public PlacementPlanState(PlacementStage stage, int testBlock, int testQuestion, int surveyQuestion,
                              String assessedLevel, LearningPlan learningPlan) {
        this(stage, testBlock, testQuestion, surveyQuestion, assessedLevel, learningPlan,
                "B1", List.of(), null, null, null, null, 0, 0);
    }

    public static PlacementPlanState start() {
        return new PlacementPlanState(PlacementStage.LADDER, 0, 0, 0, null, null);
    }

    public PlacementPlanState withStage(PlacementStage next) {
        return copy(next, testBlock, testQuestion, surveyQuestion, assessedLevel, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public PlacementPlanState withTest(int block, int question) {
        return copy(stage, block, question, surveyQuestion, assessedLevel, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public PlacementPlanState withSurvey(int question) {
        return copy(stage, testBlock, testQuestion, question, assessedLevel, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public PlacementPlanState withAssessedLevel(String level) {
        return copy(stage, testBlock, testQuestion, surveyQuestion, level, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public PlacementPlanState withLearningPlan(LearningPlan plan, String level) {
        return copy(PlacementStage.READY, testBlock, testQuestion, surveyQuestion, level, plan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public PlacementPlanState withLadder(String band, List<LadderHit> hits, String missBand, String boundary,
                                         String preliminaryLevel, String currentItemId,
                                         Integer refineAsked, Integer refineCorrect) {
        return copy(stage, testBlock, testQuestion, surveyQuestion, assessedLevel, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    private static PlacementPlanState copy(PlacementStage stage, int testBlock, int testQuestion, int surveyQuestion,
                                           String assessedLevel, LearningPlan learningPlan,
                                           String band, List<LadderHit> hits, String missBand, String boundary,
                                           String preliminaryLevel, String currentItemId,
                                           Integer refineAsked, Integer refineCorrect) {
        return new PlacementPlanState(stage, testBlock, testQuestion, surveyQuestion, assessedLevel, learningPlan,
                band, hits, missBand, boundary, preliminaryLevel, currentItemId, refineAsked, refineCorrect);
    }

    public record LadderHit(
            @JsonProperty("itemId") String itemId,
            @JsonProperty("correct") boolean correct,
            @JsonProperty("band") String band) {
    }
}
