package study.ai_foreign_language_teacher.speech;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Лёгкая правка типичных оговорок Whisper, если имя уже было в диалоге.
 * Не заменяет нормальное распознавание — только явные путаницы вроде «Was that» → «Vasia».
 */
public final class SttNameHints {

    private static final Map<String, List<String>> CONFUSIONS = Map.of(
            "Vasia", List.of("was that", "was the", "was a"),
            "Vasya", List.of("was that", "was the", "was a")
    );

    private SttNameHints() {
    }

    public static String apply(String transcript, Set<String> namesFromChat) {
        if (transcript == null || transcript.isBlank() || namesFromChat == null || namesFromChat.isEmpty()) {
            return transcript;
        }
        String trimmed = transcript.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String name : namesFromChat) {
            List<String> wrong = CONFUSIONS.get(name);
            if (wrong == null) {
                continue;
            }
            for (String prefix : wrong) {
                if (lower.startsWith(prefix)) {
                    String rest = trimmed.substring(prefix.length());
                    return name + rest;
                }
            }
        }
        return trimmed;
    }
}