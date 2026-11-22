package study.ai_foreign_language_teacher.domain;

import java.util.regex.Pattern;

/** Шкала уровней A1–C2 и то, как учитель ведёт себя на каждом (раздел 7 ARCHITECTURE.md). */
public enum Cefr {
    A1, A2, B1, B2, C1, C2;

    private static final Pattern CEFR_CODE = Pattern.compile("\\b(A1|A2|B1|B2|C1|C2)\\b", Pattern.CASE_INSENSITIVE);

    public static Cefr parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CEFR level is required (A1..C2)");
        }
        var matcher = CEFR_CODE.matcher(value.strip());
        if (matcher.find()) {
            return valueOf(matcher.group(1).toUpperCase());
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown CEFR level: " + value + " (expected A1..C2)");
        }
    }

    public static Cefr parseOr(String value, Cefr fallback) {
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public String teachingGuidance() {
        return switch (this) {
            case A1, A2 -> """
                    Use very short sentences and only high-frequency words. Speak slowly and simply.
                    Correct almost every mistake, one at a time, gently.
                    Always fill answer_ru with a Russian translation of every sentence in answer_en, and follow_up_ru with a Russian translation of the whole follow_up_question.""";
            case B1, B2 -> """
                    Keep about 80% of the lesson in English. Correct mistakes that block meaning and mistakes that repeat.
                    Explain a rule in simple English with one example; avoid grammar jargon without an example.
                    Use answer_ru and follow_up_ru only when the learner is stuck or writes in Russian. Then translate every sentence, including the question.""";
            case C1, C2 -> """
                    Talk like an educated native speaker, not like a schoolteacher.
                    Focus on nuance, register, collocations and idioms; ignore trivial slips unless they repeat.
                    Leave answer_ru and follow_up_ru empty unless the learner explicitly asks for Russian. Then translate every sentence, including the question.""";
        };
    }
}
