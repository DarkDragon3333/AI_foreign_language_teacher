package study.ai_foreign_language_teacher.practice;

import study.ai_foreign_language_teacher.domain.Cefr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
final class SpeakingPhraseSet {

    static final int SIZE = 6;

    private SpeakingPhraseSet() {
    }

    static List<String> prepare(List<String> raw, Cefr cefr) {
        List<String> fallback = fallbackPhrases(cefr);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            for (String p : raw) {
                if (p == null || p.isBlank()) {
                    continue;
                }
                String norm = p.strip();
                if (unique.stream().noneMatch(existing -> equalsIgnoreWords(existing, norm))) {
                    unique.add(norm);
                }
            }
        }
        for (String f : fallback) {
            if (unique.size() >= SIZE) {
                break;
            }
            if (unique.stream().noneMatch(existing -> equalsIgnoreWords(existing, f))) {
                unique.add(f);
            }
        }
        return new ArrayList<>(unique).subList(0, Math.min(SIZE, unique.size()));
    }

    static List<String> fallbackPhrases(Cefr cefr) {
        return switch (cefr) {
            case A1, A2 -> List.of(
                    "Good morning. How are you?",
                    "I would like a cup of tea, please.",
                    "Where is the nearest bus stop?",
                    "My name is Alex. Nice to meet you.",
                    "Can you speak more slowly?",
                    "I am learning English every day.");
            case B1, B2 -> List.of(
                    "I would like to book a table for two.",
                    "Could you tell me how to get to the station?",
                    "I have been working here since last year.",
                    "Let me know if you need any help.",
                    "We should leave early to avoid traffic.",
                    "I am not sure I understood your question.");
            default -> List.of(
                    "The proposal raises several ethical concerns.",
                    "We need to reconcile these two conflicting accounts.",
                    "Her argument hinges on a subtle distinction.",
                    "The policy may have unintended consequences.",
                    "I would appreciate your candid feedback.",
                    "We ought to revisit this assumption.");
        };
    }

    private static boolean equalsIgnoreWords(String a, String b) {
        return SpeechAligner.normalizeAnswer(a).equals(SpeechAligner.normalizeAnswer(b));
    }
}