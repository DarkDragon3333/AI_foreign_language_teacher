package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.PlacementPlanState;
import study.ai_foreign_language_teacher.domain.PlacementPlanState.LadderHit;
import study.ai_foreign_language_teacher.domain.PlacementStage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Лесенка 1 и уточнение на границе двух соседних ступеней. Модель не участвует. */
public final class PlacementLadder {

    public static final int LADDER_LIMIT = 8;
    public static final int REFINE_LIMIT = 6;
    static final List<String> BANDS = List.of("A1", "A2", "B1", "B2", "C1");

    private PlacementLadder() {
    }

    public static PlacementPlanState begin(PlacementBank bank) {
        PlacementPlanState blank = PlacementPlanState.start();
        String itemId = nextLadderItem(blank, bank);
        return blank.withLadder("B1", List.of(), null, null, null, itemId, 0, 0);
    }

    public static PlacementPlanState answer(PlacementPlanState plan, PlacementBank bank, String itemId, String choice) {
        if (plan == null || (plan.stage() != PlacementStage.LADDER && plan.stage() != PlacementStage.REFINE)) {
            throw new IllegalArgumentException("Placement ladder is not active");
        }
        if (itemId == null || !itemId.equals(plan.currentItemId())) {
            throw new IllegalArgumentException("That question is no longer active");
        }
        PlacementBank.Item item = bank.byId(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Unknown placement item");
        }
        boolean correct = item.key() != null && item.key().equalsIgnoreCase(choice == null ? "" : choice.strip());
        if (plan.stage() == PlacementStage.REFINE) {
            return answerRefine(plan, bank, item, correct);
        }
        return answerLadder(plan, bank, item, correct);
    }

    public static PlacementPlanState accept(PlacementPlanState plan) {
        if (plan == null || plan.stage() != PlacementStage.OFFER) {
            throw new IllegalArgumentException("There is no preliminary level to accept");
        }
        String level = plan.preliminaryLevel() == null ? plan.band() : plan.preliminaryLevel();
        return plan.withAssessedLevel(level).withStage(PlacementStage.SURVEY).withSurvey(0)
                .withLadder(plan.band(), plan.hits(), plan.missBand(), plan.boundary(), level, null, 0, 0);
    }

    public static PlacementPlanState beginRefine(PlacementPlanState plan, PlacementBank bank) {
        if (plan == null || plan.stage() != PlacementStage.OFFER) {
            throw new IllegalArgumentException("Refine is only available after the first ladder");
        }
        String itemId = nextBoundaryItem(plan, bank);
        if (itemId == null) {
            return accept(plan);
        }
        return plan.withStage(PlacementStage.REFINE)
                .withLadder(plan.band(), plan.hits(), null, plan.boundary(), plan.preliminaryLevel(), itemId, 0, 0);
    }

    private static PlacementPlanState answerLadder(PlacementPlanState plan, PlacementBank bank,
                                                   PlacementBank.Item item, boolean correct) {
        List<LadderHit> hits = new ArrayList<>(plan.hits());
        hits.add(new LadderHit(item.id(), correct, item.cefr()));
        String missBand = plan.missBand();
        boolean stopForMiss = false;
        if (correct) {
            missBand = null;
        } else if (item.cefr() != null && item.cefr().equals(missBand)) {
            stopForMiss = true;
        } else {
            missBand = item.cefr();
        }
        String band = shift(plan.band(), correct);
        PlacementPlanState stepped = plan.withLadder(band, hits, missBand, null, null, null, 0, 0);
        if (hits.size() >= LADDER_LIMIT || stopForMiss || nextLadderItem(stepped, bank) == null) {
            return toOffer(stepped);
        }
        return stepped.withLadder(band, hits, missBand, null, null, nextLadderItem(stepped, bank), 0, 0);
    }

    private static PlacementPlanState answerRefine(PlacementPlanState plan, PlacementBank bank,
                                                   PlacementBank.Item item, boolean correct) {
        int asked = plan.refineAsked() + 1;
        int got = plan.refineCorrect() + (correct ? 1 : 0);
        List<LadderHit> hits = new ArrayList<>(plan.hits());
        hits.add(new LadderHit(item.id(), correct, item.cefr()));
        if (asked >= REFINE_LIMIT) {
            String level = refineLevel(plan.preliminaryLevel(), plan.boundary(), got);
            return plan.withAssessedLevel(level).withStage(PlacementStage.SURVEY).withSurvey(0)
                    .withLadder(level, hits, null, plan.boundary(), plan.preliminaryLevel(), null, asked, got);
        }
        PlacementPlanState mid = plan.withLadder(plan.band(), hits, null, plan.boundary(),
                plan.preliminaryLevel(), null, asked, got);
        return mid.withLadder(plan.band(), hits, null, plan.boundary(), plan.preliminaryLevel(),
                nextBoundaryItem(mid, bank), asked, got);
    }

    static PlacementPlanState toOffer(PlacementPlanState plan) {
        String preliminary = preliminaryLevel(plan.hits());
        String boundary = boundaryFor(preliminary, plan.hits());
        return plan.withStage(PlacementStage.OFFER)
                .withLadder(preliminary, plan.hits(), null, boundary, preliminary, null, 0, 0);
    }

    static String preliminaryLevel(List<LadderHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "B1";
        }
        double weighted = 0;
        double weight = 0;
        for (int i = 0; i < hits.size(); i++) {
            int band = BANDS.indexOf(hits.get(i).band());
            if (band < 0) {
                continue;
            }
            double w = i + 1.0;
            weighted += band * w;
            weight += w;
        }
        int idx = weight == 0 ? BANDS.indexOf("B1") : (int) Math.round(weighted / weight);
        int cap = BANDS.size() - 1;
        for (int i = 0; i < BANDS.size(); i++) {
            if (wrongCount(hits, BANDS.get(i)) > 0) {
                cap = Math.min(cap, i + 1);
                break;
            }
        }
        for (int i = 0; i < BANDS.size(); i++) {
            int correct = correctCount(hits, BANDS.get(i));
            int wrong = wrongCount(hits, BANDS.get(i));
            if (correct + wrong >= 2 && wrong > correct) {
                cap = Math.min(cap, i);
                break;
            }
        }
        if (idx > cap) {
            idx = cap;
        }
        int floor = -1;
        for (int i = 0; i <= cap && i < BANDS.size(); i++) {
            int correct = correctCount(hits, BANDS.get(i));
            int wrong = wrongCount(hits, BANDS.get(i));
            if (correct >= 2 && correct > wrong) {
                floor = i;
            }
        }
        if (floor >= 0 && idx < floor) {
            idx = floor;
        }
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= BANDS.size()) {
            idx = BANDS.size() - 1;
        }
        return BANDS.get(idx);
    }

    static String boundaryFor(String preliminary, List<LadderHit> hits) {
        int pre = BANDS.indexOf(preliminary);
        if (pre < 0) {
            pre = BANDS.indexOf("B1");
        }
        if (pre == 0) {
            return BANDS.get(0) + "-" + BANDS.get(1);
        }
        if (pre == BANDS.size() - 1) {
            return BANDS.get(pre - 1) + "-" + BANDS.get(pre);
        }
        int above = 0;
        int below = 0;
        for (LadderHit hit : hits) {
            int at = BANDS.indexOf(hit.band());
            if (at > pre) {
                above++;
            } else if (at >= 0 && at < pre) {
                below++;
            }
        }
        int neighbor = above > below ? pre + 1 : pre - 1;
        int low = Math.min(pre, neighbor);
        int high = Math.max(pre, neighbor);
        return BANDS.get(low) + "-" + BANDS.get(high);
    }

    static String refineLevel(String preliminary, String boundary, int correct) {
        if (boundary == null || !boundary.contains("-")) {
            return preliminary == null ? "B1" : preliminary;
        }
        String[] parts = boundary.split("-");
        String lower = parts[0];
        String upper = parts.length > 1 ? parts[1] : parts[0];
        if (correct >= 4) {
            return upper;
        }
        if (correct <= 2) {
            return lower;
        }
        return preliminary == null ? lower : preliminary;
    }

    static String nextLadderItem(PlacementPlanState plan, PlacementBank bank) {
        String direct = firstUnseen(bank.ladder(plan.band()), plan.hits());
        if (direct != null) {
            return direct;
        }
        int idx = Math.max(0, BANDS.indexOf(plan.band()));
        for (int dist = 1; dist < BANDS.size(); dist++) {
            for (int sign : new int[] { -1, 1 }) {
                int at = idx + sign * dist;
                if (at < 0 || at >= BANDS.size()) {
                    continue;
                }
                String id = firstUnseen(bank.ladder(BANDS.get(at)), plan.hits());
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    static String nextBoundaryItem(PlacementPlanState plan, PlacementBank bank) {
        return firstUnseen(bank.boundary(plan.boundary()), plan.hits());
    }

    private static String firstUnseen(List<PlacementBank.Item> pool, List<LadderHit> hits) {
        return pickUnseen(pool, hits, ThreadLocalRandom.current());
    }

    static String pickUnseen(List<PlacementBank.Item> pool, List<LadderHit> hits, java.util.Random random) {
        List<String> unseen = new ArrayList<>();
        for (PlacementBank.Item item : pool) {
            boolean seen = false;
            for (LadderHit hit : hits) {
                if (item.id().equals(hit.itemId())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                unseen.add(item.id());
            }
        }
        if (unseen.isEmpty()) {
            return null;
        }
        return unseen.get(random.nextInt(unseen.size()));
    }

    private static int correctCount(List<LadderHit> hits, String band) {
        int count = 0;
        for (LadderHit hit : hits) {
            if (hit.correct() && band.equals(hit.band())) {
                count++;
            }
        }
        return count;
    }

    private static int wrongCount(List<LadderHit> hits, String band) {
        int count = 0;
        for (LadderHit hit : hits) {
            if (!hit.correct() && band.equals(hit.band())) {
                count++;
            }
        }
        return count;
    }

    private static String shift(String band, boolean up) {
        int i = BANDS.indexOf(band);
        if (i < 0) {
            i = BANDS.indexOf("B1");
        }
        int next = up ? i + 1 : i - 1;
        if (next < 0) {
            next = 0;
        }
        if (next >= BANDS.size()) {
            next = BANDS.size() - 1;
        }
        return BANDS.get(next);
    }
}
