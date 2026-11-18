package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.TeacherReply;

import java.util.Locale;

/**
 * Свободный пример модели показываем, только если в нём есть соседние слова из фразы ученика.
 * «He ride his bike» при фразе про «my bike» — чужой пример, его не выводим.
 */
final class ExplanationGate {

    private ExplanationGate() {
    }

    static boolean quotesLearner(String userText, TeacherReply.Explanation explanation, boolean genericOk) {
        if (explanation == null) {
            return false;
        }
        if (genericOk) {
            return true;
        }
        return sharesPhrase(userText, explanation.exampleBad()) || sharesPhrase(userText, explanation.simple());
    }

    static boolean sharesPhrase(String userText, String sample) {
        if (userText == null || sample == null) {
            return false;
        }
        String[] sampleWords = words(sample);
        String haystack = " " + String.join(" ", words(userText)) + " ";
        for (int i = 0; i + 1 < sampleWords.length; i++) {
            if (sampleWords[i].length() < 2 || sampleWords[i + 1].length() < 2) {
                continue;
            }
            if (haystack.contains(" " + sampleWords[i] + " " + sampleWords[i + 1] + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String[] words(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[^\\p{L}\\p{N}']+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return new String[0];
        }
        return cleaned.split("\\s+");
    }
}
