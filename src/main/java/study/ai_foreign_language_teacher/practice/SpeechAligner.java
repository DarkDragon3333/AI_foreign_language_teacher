package study.ai_foreign_language_teacher.practice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SpeechAligner {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9'\\-]+");

    private SpeechAligner() {
    }

    public record WordMismatch(String expected, String heard, String kind) {
    }

    public static List<WordMismatch> compare(String expected, String heard) {
        String[] exp = tokens(expected);
        String[] got = tokens(heard);
        List<WordMismatch> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < exp.length || j < got.length) {
            if (i >= exp.length) {
                out.add(new WordMismatch("", got[j], "extra"));
                j++;
                continue;
            }
            if (j >= got.length) {
                out.add(new WordMismatch(exp[i], "", "missing"));
                i++;
                continue;
            }
            if (exp[i].equals(got[j])) {
                i++;
                j++;
                continue;
            }
            if (j + 1 < got.length && exp[i].equals(got[j + 1])) {
                out.add(new WordMismatch("", got[j], "extra"));
                j++;
                continue;
            }
            if (i + 1 < exp.length && got[j].equals(exp[i + 1])) {
                out.add(new WordMismatch(exp[i], "", "missing"));
                i++;
                continue;
            }
            out.add(new WordMismatch(exp[i], got[j], "wrong"));
            i++;
            j++;
        }
        return out.stream().filter(m -> !m.expected().isEmpty() || !m.heard().isEmpty()).toList();
    }

    public static String[] tokens(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        String norm = NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (norm.isEmpty()) {
            return new String[0];
        }
        return norm.split("\\s+");
    }

    public static String normalizeAnswer(String text) {
        if (text == null) {
            return "";
        }
        return tokens(text).length == 0 ? "" : String.join(" ", tokens(text));
    }
}