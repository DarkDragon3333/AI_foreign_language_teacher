package study.ai_foreign_language_teacher.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ошибки, которые LanguageTool в этой фразе не размечает.
 * «It's help me» — это не «it is help», а «it helps». «tp» перед глаголом — опечатка to.
 */
public final class AgreementPatterns {

    private static final Pattern AT_THE_MY_PLACE = Pattern.compile(
            "(?i)\\bat\\s+the\\s+(my|your|his|her|our|their)\\s+(city|town)\\b");
    private static final Pattern ITS_HELP = Pattern.compile("(?i)\\bit's\\s+help\\b");
    private static final Pattern TP_TO = Pattern.compile(
            "(?i)\\btp\\b(?=\\s+(?:relax|rest|sleep|study|learn|work|read|play|ride|go|come|think|focus|unwind|walk|run)\\b)");

    private AgreementPatterns() {
    }

    public static List<LtMatch> supplement(String text, List<LtMatch> found) {
        List<LtMatch> out = new ArrayList<>(found == null ? List.of() : found);
        if (text == null || text.isBlank()) {
            return List.copyOf(out);
        }
        add(out, text, AT_THE_MY_PLACE, "A_MY", "Grammar", "the my",
                "Do not put the before my. In a city, say in my city.",
                m -> (Character.isUpperCase(m.group().charAt(0)) ? "In " : "in ")
                        + m.group(1).toLowerCase(Locale.ROOT) + " " + m.group(2).toLowerCase(Locale.ROOT));
        add(out, text, ITS_HELP, "IT_HELPS", "Grammar", "he, she, it + -s",
                "It's means it is. For a habit, say it helps.",
                m -> Character.isUpperCase(m.group().charAt(0)) ? "It helps" : "it helps");
        add(out, text, TP_TO, "TP_TO", "Grammar", "to", "→ to", m -> "to");
        return List.copyOf(out);
    }

    private static void add(List<LtMatch> out, String text, Pattern pattern, String ruleId,
                            String category, String title, String message, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int offset = matcher.start();
            int length = matcher.end() - matcher.start();
            out.removeIf(m -> overlaps(m.offset(), m.length(), offset, length));
            String span = text.substring(offset, offset + length);
            out.add(new LtMatch(ruleId, category, title, message, offset, length, span, List.of(replacement.apply(matcher))));
        }
    }

    private static boolean overlaps(int start, int length, int otherStart, int otherLength) {
        return start < otherStart + otherLength && otherStart < start + length;
    }
}
