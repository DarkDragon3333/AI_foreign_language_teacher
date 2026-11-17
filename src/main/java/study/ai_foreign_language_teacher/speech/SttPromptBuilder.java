package study.ai_foreign_language_teacher.speech;

import study.ai_foreign_language_teacher.domain.ChatMessage;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Подсказка Whisper: тема урока и имена из диалога (часто русские в латинице). */
public final class SttPromptBuilder {

    private static final int MAX_LEN = 220;
    private static final Pattern CAPITAL = Pattern.compile("\\b[A-Z][a-z]{1,24}\\b");
    /** Русские имена, которые ученики часто произносят по-английски. */
    private static final String COMMON_NAMES =
            "Vasia, Vasya, Dasha, Masha, Sasha, Petya, Katya, Natasha, Olya, Anya, Igor, Boris";

    private SttPromptBuilder() {
    }

    public static String forLesson(List<ChatMessage> recent, String topic) {
        Set<String> names = new LinkedHashSet<>();
        for (ChatMessage m : recent) {
            if (m.text() == null) {
                continue;
            }
            Matcher matcher = CAPITAL.matcher(m.text());
            while (matcher.find()) {
                String word = matcher.group();
                if (!isCommonEnglish(word)) {
                    names.add(word);
                }
            }
        }
        StringBuilder sb = new StringBuilder("English lesson. ");
        if (topic != null && !topic.isBlank()) {
            sb.append("Topic: ").append(topic.strip()).append(". ");
        }
        sb.append("Russian names in Latin may appear: ").append(COMMON_NAMES);
        if (!names.isEmpty()) {
            sb.append(". From chat: ").append(String.join(", ", names));
        }
        sb.append('.');
        if (sb.length() > MAX_LEN) {
            return sb.substring(0, MAX_LEN);
        }
        return sb.toString();
    }

    public static Set<String> namesFrom(List<ChatMessage> recent) {
        Set<String> names = new LinkedHashSet<>();
        for (ChatMessage m : recent) {
            if (m.text() == null) {
                continue;
            }
            Matcher matcher = CAPITAL.matcher(m.text());
            while (matcher.find()) {
                String word = matcher.group();
                if (!isCommonEnglish(word)) {
                    names.add(word);
                }
            }
        }
        return names;
    }

    private static boolean isCommonEnglish(String word) {
        return switch (word) {
            case "I", "Hello", "Yes", "No", "What", "Does", "Do", "The", "That", "This", "English", "Russian" -> true;
            default -> false;
        };
    }
}