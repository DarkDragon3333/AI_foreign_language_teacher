package study.ai_foreign_language_teacher.session;

import java.util.List;

/** Грубое правило: ученик пишет, что не понимает, или просит помощи. */
public final class StuckDetector {

    /** Говорят о затруднении в любом месте реплики. */
    private static final List<String> ANYWHERE = List.of(
            "не понимаю", "не понял", "не поняла", "непонятно", "помоги", "подскажи", "подсказк",
            "что это значит", "как сказать", "объясни",
            "i don't understand", "i dont understand", "i do not understand", "don't get it",
            "give me a hint", "what does it mean");

    /** «Не знаю» — затруднение, только если это вся реплика: «I don't know what I want to do» — обычный ответ. */
    private static final List<String> WHOLE_MESSAGE = List.of("не знаю", "i don't know", "i dont know", "i do not know", "no idea");

    private StuckDetector() {
    }

    public static boolean isStuck(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase().replace('\u2019', '\'').strip();
        if (ANYWHERE.stream().anyMatch(t::contains)) {
            return true;
        }
        if (asksForHelp(t)) {
            return true;
        }
        String bare = t.replaceAll("[\\p{Punct}\\s]+$", "").replaceAll("^(sorry|извини|извините)[,\\s]+", "");
        return WHOLE_MESSAGE.contains(bare);
    }

    /**
     * «help me» — просьба к учителю, только если это обращение, а не «это помогает мне».
     * «It's help me to relax» содержит те же слова, но это обычная фраза.
     */
    static boolean asksForHelp(String text) {
        return text.matches("(?s).*(\\A|[.!?,]\\s*|\\bplease\\s+|\\b(?:can|could|would) you\\s+)help me(?!\\p{L}).*");
    }
}
