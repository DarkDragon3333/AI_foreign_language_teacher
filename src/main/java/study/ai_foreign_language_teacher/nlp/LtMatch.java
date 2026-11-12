package study.ai_foreign_language_teacher.nlp;

import java.util.List;

/** Одна находка LanguageTool в тексте ученика. */
public record LtMatch(
        String ruleId,
        String category,
        String title,
        String message,
        int offset,
        int length,
        String span,
        List<String> replacements) {

    /** Орфография (словарь Morfologik), а не грамматика. */
    public static boolean isSpellingRule(String ruleId) {
        return ruleId != null && (ruleId.startsWith("MORFOLOGIK_") || ruleId.contains("SPELL"));
    }

    public boolean spelling() {
        return isSpellingRule(ruleId);
    }

    public String firstReplacement() {
        return replacements.isEmpty() ? null : replacements.get(0);
    }
}
