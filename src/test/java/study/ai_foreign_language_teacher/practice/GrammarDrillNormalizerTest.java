package study.ai_foreign_language_teacher.practice;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.GrammarExerciseType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrammarDrillNormalizerTest {

    private static GrammarPracticeService.Exercise ex(String type, String prompt, List<String> choices, String answer) {
        return new GrammarPracticeService.Exercise(type, prompt, choices, answer, "rule");
    }

    @Test
    void rejectsChoiceWhenAnswerNotAmongOptions() {
        var bad = ex("choice", "___ I go to the park?", List.of("Do", "Does", "Am"), "Go");
        assertFalse(GrammarDrillNormalizer.isValid(bad, Set.of(GrammarExerciseType.CHOICE)));
    }

    @Test
    void rejectsGapWithoutBlank() {
        var bad = ex("gap", "She goes to work every day.", List.of(), "goes");
        assertFalse(GrammarDrillNormalizer.isValid(bad, Set.of(GrammarExerciseType.GAP)));
    }

    @Test
    void rejectsTrailingBlankAfterBrokenVerb() {
        var bad = ex("choice", "She go to school ___ every day.", List.of("go", "goes", "going"), "goes");
        assertFalse(GrammarDrillNormalizer.isValid(bad, Set.of(GrammarExerciseType.CHOICE)));
    }

    @Test
    void acceptsCleanGapAndChoice() {
        var gap = ex("gap", "I ___ happy today.", List.of(), "am");
        var choice = ex("choice", "___ you like tea?", List.of("Do", "Does", "Are"), "Do");
        assertTrue(GrammarDrillNormalizer.isValid(gap, Set.of(GrammarExerciseType.GAP)));
        assertTrue(GrammarDrillNormalizer.isValid(choice, Set.of(GrammarExerciseType.CHOICE)));
    }
}