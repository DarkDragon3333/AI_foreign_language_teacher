package study.ai_foreign_language_teacher.practice;

import study.ai_foreign_language_teacher.domain.Cefr;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TextComplexity {

    private static final Pattern SENTENCE = Pattern.compile("[.!?]+");
    private static final Pattern WORD = Pattern.compile("[a-zA-Z']+");

    private TextComplexity() {
    }

    public static String warnIfHard(String text, Cefr level) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int sentences = Math.max(1, SENTENCE.split(text).length);
        String[] words = WORD.matcher(text).results().map(m -> m.group().toLowerCase(Locale.ROOT)).toArray(String[]::new);
        if (words.length == 0) {
            return null;
        }
        double avgLen = (double) words.length / sentences;
        int longWords = 0;
        for (String w : words) {
            if (w.length() >= 10) {
                longWords++;
            }
        }
        double longRatio = (double) longWords / words.length;
        double maxAvg = switch (level) {
            case A1, A2 -> 12;
            case B1 -> 16;
            case B2 -> 20;
            default -> 24;
        };
        double maxLong = switch (level) {
            case A1, A2 -> 0.08;
            case B1 -> 0.14;
            case B2 -> 0.2;
            default -> 0.28;
        };
        if (avgLen > maxAvg || longRatio > maxLong) {
            return "This text may be hard for " + level.name() + ". Shorten it or pick an easier passage.";
        }
        return null;
    }
}