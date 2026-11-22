package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.TeacherReply;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Чинит типичные сбои маленькой модели, которые видны ученику:
 * учитель повторяет фразу ученика вместо ответа и задаёт один и тот же вопрос по кругу.
 */
public final class ReplyPolish {

    private ReplyPolish() {
    }

    /** Если reply_en — это пересказ реплики ученика, убираем его и оставляем только вопрос. */
    public static TeacherReply dropEcho(TeacherReply reply, String userText) {
        if (userText == null || userText.isBlank() || !similar(reply.replyEn(), userText)) {
            return reply;
        }
        if (reply.nextPrompt() == null) {
            return reply; // показать больше нечего — пусть решает проверка на повтор
        }
        return new TeacherReply(reply.nextPrompt(), null, reply.correction(),
                reply.explanation(), null, reply.cardsToReview(), reply.mode(), reply.cefr(), reply.supportLevel(),
                reply.readingPassage());
    }

    /** Учитель повторил свою недавнюю реплику или просто вернул ученику его же слова. */
    public static boolean isStale(TeacherReply reply, String userText, List<String> recentTeacherTexts) {
        String question = reply.nextPrompt() != null ? reply.nextPrompt() : reply.replyEn();
        for (String earlier : recentTeacherTexts) {
            if (norm(earlier).contains(norm(question)) || similar(earlier, reply.displayText())) {
                return true;
            }
        }
        return reply.nextPrompt() == null && userText != null && !userText.isBlank() && similar(reply.replyEn(), userText);
    }

    /** Похожесть по набору слов: «I like the end work day» ~ «I like the end of work.» */
    public static boolean similar(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        Set<String> wa = words(a);
        Set<String> wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) {
            return false;
        }
        Set<String> common = new HashSet<>(wa);
        common.retainAll(wb);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        return common.size() >= union.size() * 0.6;
    }

    /** Слова без местоимений, чтобы «You write tests» и «I write tests» считались одним и тем же. */
    private static Set<String> words(String s) {
        Set<String> skip = Set.of("i", "you", "my", "your", "me", "am", "are", "is", "the", "a", "an");
        return Arrays.stream(norm(s).split(" "))
                .filter(w -> !w.isEmpty() && !skip.contains(w))
                .collect(Collectors.toSet());
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
