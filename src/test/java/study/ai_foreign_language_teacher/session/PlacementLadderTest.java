package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.PlacementPlanState;
import study.ai_foreign_language_teacher.domain.PlacementPlanState.LadderHit;
import study.ai_foreign_language_teacher.domain.PlacementStage;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementLadderTest {

    private final PlacementBank bank = new PlacementBank();

    @Test
    void correctAnswerMovesUpABand() {
        PlacementPlanState plan = PlacementLadder.begin(bank);
        PlacementBank.Item item = bank.byId(plan.currentItemId());
        assertEquals("B1", item.cefr());
        assertTrue(item.id().startsWith("b1-"));
        PlacementPlanState next = PlacementLadder.answer(plan, bank, item.id(), item.key());
        assertEquals(PlacementStage.LADDER, next.stage());
        assertEquals("B2", next.band());
        assertEquals("B1", next.hits().get(0).band());
    }

    @Test
    void eightAnswersOpenAnOffer() {
        PlacementPlanState plan = PlacementLadder.begin(bank);
        for (int i = 0; i < 8; i++) {
            PlacementBank.Item item = bank.byId(plan.currentItemId());
            plan = PlacementLadder.answer(plan, bank, item.id(), item.key());
        }
        assertEquals(PlacementStage.OFFER, plan.stage());
        assertNotNull(plan.preliminaryLevel());
        assertTrue(plan.boundary().contains("-"));
        assertNull(plan.currentItemId());
    }

    @Test
    void acceptKeepsPreliminaryLevel() {
        PlacementPlanState offer = PlacementLadder.toOffer(PlacementLadder.begin(bank));
        PlacementPlanState survey = PlacementLadder.accept(offer);
        assertEquals(PlacementStage.SURVEY, survey.stage());
        assertEquals(offer.preliminaryLevel(), survey.assessedLevel());
        assertEquals(0, survey.surveyQuestion());
    }

    @Test
    void refineFourCorrectTakesTheUpperBand() {
        PlacementPlanState offer = PlacementLadder.toOffer(PlacementLadder.begin(bank))
                .withLadder("B1", java.util.List.of(), null, "B1-B2", "B1", null, 0, 0);
        PlacementPlanState refine = PlacementLadder.beginRefine(offer, bank);
        assertEquals(PlacementStage.REFINE, refine.stage());
        for (int i = 0; i < 6; i++) {
            PlacementBank.Item item = bank.byId(refine.currentItemId());
            String choice = i < 4 ? item.key() : other(item.key());
            refine = PlacementLadder.answer(refine, bank, item.id(), choice);
        }
        assertEquals(PlacementStage.SURVEY, refine.stage());
        assertEquals("B2", refine.assessedLevel());
    }

    @Test
    void missAtB1CapsBelowC1() {
        List<LadderHit> hits = List.of(
                hit("b1", false, "B1"),
                hit("c1a", true, "C1"),
                hit("c1b", true, "C1"));
        assertEquals("B2", PlacementLadder.preliminaryLevel(hits));
    }

    @Test
    void singleCorrectOnEveryBandIsNotC1() {
        List<LadderHit> hits = List.of(
                hit("a2", true, "A2"),
                hit("b1", true, "B1"),
                hit("b2", true, "B2"),
                hit("c1", true, "C1"));
        assertEquals("B2", PlacementLadder.preliminaryLevel(hits));
    }

    @Test
    void mixedA2AndB1DoesNotFallToA1() {
        List<LadderHit> hits = List.of(
                hit("a2a", true, "A2"),
                hit("b1a", true, "B1"),
                hit("b1b", true, "B1"),
                hit("a2b", true, "A2"),
                hit("b1c", false, "B1"));
        assertEquals("B1", PlacementLadder.preliminaryLevel(hits));
    }

    @Test
    void cleanRunFromB1ToC1StaysC1() {
        List<LadderHit> hits = List.of(
                hit("b1a", true, "B1"),
                hit("b1b", true, "B1"),
                hit("b2a", true, "B2"),
                hit("b2b", true, "B2"),
                hit("c1a", true, "C1"),
                hit("c1b", true, "C1"));
        assertEquals("C1", PlacementLadder.preliminaryLevel(hits));
    }

    @Test
    void solidA2WithB1MissesStaysAtMostB1() {
        List<LadderHit> hits = List.of(
                hit("a2a", true, "A2"),
                hit("a2b", true, "A2"),
                hit("b1a", false, "B1"),
                hit("b1b", false, "B1"));
        assertEquals("B1", PlacementLadder.preliminaryLevel(hits));
    }

    @Test
    void unseenItemsAreNotAlwaysTheFirstInTheBank() {
        List<PlacementBank.Item> pool = bank.ladder("B1");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int seed = 0; seed < 40; seed++) {
            ids.add(PlacementLadder.pickUnseen(pool, List.of(), new java.util.Random(seed)));
        }
        assertTrue(ids.size() > 1);
    }

    @Test
    void threeOfSixKeepsPreliminary() {
        assertEquals("B1", PlacementLadder.refineLevel("B1", "B1-B2", 3));
        assertEquals("A2", PlacementLadder.refineLevel("B1", "A2-B1", 1));
        assertEquals("B2", PlacementLadder.refineLevel("B1", "B1-B2", 5));
    }

    @Test
    void oldPlanJsonStillReads() throws Exception {
        String json = """
                {"stage":"SURVEY","testBlock":4,"testQuestion":0,"surveyQuestion":0,"assessedLevel":"B1","learningPlan":null}
                """;
        PlacementPlanState plan = new ObjectMapper().readValue(json, PlacementPlanState.class);
        assertEquals(PlacementStage.SURVEY, plan.stage());
        assertEquals("B1", plan.assessedLevel());
        assertEquals("B1", plan.band());
        assertTrue(plan.hits().isEmpty());
    }

    private static String other(String key) {
        return "A".equalsIgnoreCase(key) ? "B" : "A";
    }

    private static LadderHit hit(String id, boolean correct, String band) {
        return new LadderHit(id, correct, band);
    }
}
