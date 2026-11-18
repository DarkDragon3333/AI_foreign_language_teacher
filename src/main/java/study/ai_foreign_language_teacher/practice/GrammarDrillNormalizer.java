package study.ai_foreign_language_teacher.practice;

import study.ai_foreign_language_teacher.domain.GrammarExerciseType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class GrammarDrillNormalizer {

    private static final int TARGET = 6;
    private static final Pattern THIRD_GO = Pattern.compile("\\b(he|she|it)\\s+go\\b", Pattern.CASE_INSENSITIVE);

    private GrammarDrillNormalizer() {
    }

    static List<GrammarPracticeService.Exercise> prepare(
            List<GrammarPracticeService.Exercise> raw,
            List<GrammarExerciseType> allowed,
            List<GrammarPracticeService.Exercise> fallback) {
        Set<GrammarExerciseType> set = new HashSet<>(allowed);
        List<GrammarPracticeService.Exercise> out = new ArrayList<>();
        if (raw != null) {
            for (GrammarPracticeService.Exercise ex : raw) {
                GrammarPracticeService.Exercise fixed = sanitize(ex, set);
                if (fixed != null) {
                    out.add(fixed);
                }
            }
        }
        int i = 0;
        while (out.size() < TARGET) {
            GrammarPracticeService.Exercise fb = fallback.get(i % fallback.size());
            if (set.contains(GrammarExerciseType.parse(fb.type()))) {
                out.add(fb);
            }
            i++;
            if (i > fallback.size() * 3) {
                break;
            }
        }
        return out.size() > TARGET ? out.subList(0, TARGET) : out;
    }

    static boolean isValid(GrammarPracticeService.Exercise ex, Set<GrammarExerciseType> allowed) {
        return sanitize(ex, allowed) != null;
    }

    private static GrammarPracticeService.Exercise sanitize(GrammarPracticeService.Exercise ex, Set<GrammarExerciseType> allowed) {
        if (ex == null || ex.prompt() == null || ex.answer() == null) {
            return null;
        }
        String prompt = ex.prompt().strip();
        String answer = ex.answer().strip();
        if (prompt.isBlank() || answer.isBlank()) {
            return null;
        }
        GrammarExerciseType type;
        try {
            type = GrammarExerciseType.parse(ex.type());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!allowed.contains(type)) {
            return null;
        }
        return switch (type) {
            case GAP -> validGap(prompt, answer, ex.rule());
            case CHOICE -> validChoice(prompt, answer, ex.choices(), ex.rule());
            case REWRITE -> validOpen(prompt, answer, ex.rule(), false);
            case FIND_ERROR -> validOpen(prompt, answer, ex.rule(), true);
        };
    }

    private static GrammarPracticeService.Exercise validGap(String prompt, String answer, String rule) {
        if (!hasBlank(prompt)) {
            return null;
        }
        if (countBlanks(prompt) > 1) {
            return null;
        }
        return new GrammarPracticeService.Exercise("gap", prompt, List.of(), answer, rule == null ? "" : rule);
    }

    private static GrammarPracticeService.Exercise validChoice(String prompt, String answer, List<String> choices, String rule) {
        if (choices == null || choices.size() < 2) {
            return null;
        }
        List<String> clean = choices.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::strip)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (clean.size() < 2) {
            return null;
        }
        String normAnswer = SpeechAligner.normalizeAnswer(answer);
        String matched = clean.stream()
                .filter(c -> SpeechAligner.normalizeAnswer(c).equals(normAnswer))
                .findFirst()
                .orElse(null);
        if (matched == null) {
            return null;
        }
        if (!hasBlank(prompt) && !prompt.contains("?")) {
            return null;
        }
        if (THIRD_GO.matcher(prompt).find()) {
            return null;
        }
        return new GrammarPracticeService.Exercise("choice", prompt, clean, matched, rule == null ? "" : rule);
    }

    private static GrammarPracticeService.Exercise validOpen(String prompt, String answer, String rule, boolean findError) {
        if (hasBlank(prompt)) {
            return null;
        }
        if (findError && prompt.equalsIgnoreCase(answer.strip())) {
            return null;
        }
        String type = findError ? "find_error" : "rewrite";
        return new GrammarPracticeService.Exercise(type, prompt, List.of(), answer, rule == null ? "" : rule);
    }

    private static boolean hasBlank(String prompt) {
        return prompt.contains("___") || prompt.contains("____");
    }

    private static int countBlanks(String prompt) {
        int n = 0;
        for (int i = 0; i < prompt.length() - 2; i++) {
            if (prompt.startsWith("___", i)) {
                n++;
            }
        }
        return n;
    }

}