package study.ai_foreign_language_teacher.session;

import java.util.List;

/** Грубое правило: ученик пишет, что не понимает / просит помощи. */
public final class StuckDetector {

    private static final List<String> MARKERS = List.of(
            "не понимаю", "не понял", "не поняла", "непонятно", "не знаю", "помоги", "подскажи", "подсказк",
            "что это значит", "как сказать", "объясни",
            "i don't understand", "i dont understand", "i do not understand", "don't get it",
            "i don't know", "i dont know", "help me", "give me a hint", "what does it mean");

    private StuckDetector() {
    }

    public static boolean isStuck(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase().replace('’', '\'');
        return MARKERS.stream().anyMatch(t::contains);
    }
}
