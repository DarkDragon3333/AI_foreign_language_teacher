package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Решает, что показать ученику как «Исправление».
 * Принцип архитектуры: LanguageTool первый, LLM объясняет размеченное и не выдумывает грамматику.
 * Находкам LT верим. Исправление от модели принимаем только если она точно указала ошибочный фрагмент
 * в тексте ученика и не просто укоротила или пересказала фразу.
 */
public final class CorrectionPolicy {

    private CorrectionPolicy() {
    }

    /**
     * Находкам грамматики верим сразу. Орфографическим — только если модель сама сделала ту же замену:
     * словарь LanguageTool не знает имён и марок и «исправляет» lada на Lady.
     */
    public static List<LtMatch> confirm(List<LtMatch> lt, TeacherReply.Correction fromModel) {
        String corrected = fromModel == null || fromModel.corrected() == null ? "" : fromModel.corrected().toLowerCase(Locale.ROOT);
        List<LtMatch> trusted = new ArrayList<>();
        for (LtMatch m : lt) {
            if (!m.spelling()) {
                trusted.add(m);
                continue;
            }
            String span = m.span().toLowerCase(Locale.ROOT);
            for (String replacement : m.replacements()) {
                if (containsWords(corrected, replacement.toLowerCase(Locale.ROOT)) && !containsWords(corrected, span)) {
                    // оставляем ту замену, которую подтвердила модель
                    trusted.add(new LtMatch(m.ruleId(), m.category(), m.title(), m.message(), m.offset(), m.length(),
                            m.span(), List.of(replacement)));
                    break;
                }
            }
        }
        return trusted;
    }

    /** @param lt находки LanguageTool, уже прошедшие {@link #confirm} */
    public static TeacherReply.Correction decide(String userText, TeacherReply.Correction fromModel, List<LtMatch> lt) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        List<TeacherReply.ErrorItem> errors = new ArrayList<>();
        for (LtMatch m : lt) {
            String rule = m.title() == null || m.title().isBlank() ? m.category() : m.title();
            errors.add(new TeacherReply.ErrorItem(m.span(), rule, hint(m)));
        }

        List<TeacherReply.ErrorItem> modelErrors = validModelErrors(userText, fromModel, lt);
        boolean modelTrusted = fromModel != null && !modelErrors.isEmpty() && isRealRewrite(userText, fromModel.corrected());
        if (modelTrusted) {
            errors.addAll(modelErrors);
        }
        if (errors.isEmpty()) {
            return null;
        }

        // Текст модели берём, только когда она сама точно указала ошибку; иначе подставляем замены LT в исходную фразу —
        // так в «исправленном» не появится ничего, чего нет в списке ошибок.
        String corrected = modelTrusted ? fromModel.corrected().strip() : applyReplacements(userText, lt);
        return new TeacherReply.Correction(userText, sameText(corrected, userText) ? null : corrected, errors);
    }

    /** Ошибки модели, у которых фрагмент дословно есть в тексте ученика и не дублирует находку LT. */
    static List<TeacherReply.ErrorItem> validModelErrors(String userText, TeacherReply.Correction fromModel, List<LtMatch> lt) {
        if (fromModel == null || fromModel.errors() == null) {
            return List.of();
        }
        String haystack = userText.toLowerCase(Locale.ROOT);
        List<TeacherReply.ErrorItem> valid = new ArrayList<>();
        for (TeacherReply.ErrorItem e : fromModel.errors()) {
            if (e.span() == null || e.rule() == null) {
                continue;
            }
            String span = e.span().strip().toLowerCase(Locale.ROOT);
            // фрагмент должен быть частью фразы, а не всей фразой целиком
            if (span.isEmpty() || !containsWords(haystack, span) || span.length() > haystack.length() * 0.7) {
                continue;
            }
            // модель должна была действительно изменить этот фрагмент
            if (fromModel.corrected() != null && containsWords(fromModel.corrected().toLowerCase(Locale.ROOT), span)
                    && haystack.indexOf(span) == haystack.lastIndexOf(span)) {
                continue;
            }
            boolean duplicate = lt.stream().anyMatch(m -> overlaps(m.span().toLowerCase(Locale.ROOT), span));
            if (!duplicate) {
                valid.add(e);
            }
        }
        return valid;
    }

    /** Исправление — это не та же фраза и не её обрезок. */
    static boolean isRealRewrite(String original, String corrected) {
        if (corrected == null || corrected.isBlank() || sameText(original, corrected)) {
            return false;
        }
        String o = normalize(original);
        String c = normalize(corrected);
        if (o.contains(c)) {
            return false; // модель просто выкинула часть текста
        }
        return c.length() >= o.length() * 0.6 && c.length() <= o.length() * 1.6 + 10;
    }

    /** Подставляет первую замену LT для каждой находки; идём с конца, чтобы не сбить смещения. */
    static String applyReplacements(String text, List<LtMatch> lt) {
        StringBuilder sb = new StringBuilder(text);
        int lastStart = Integer.MAX_VALUE;
        List<LtMatch> sorted = lt.stream().sorted(Comparator.comparingInt(LtMatch::offset).reversed()).toList();
        for (LtMatch m : sorted) {
            int end = m.offset() + m.length();
            if (m.firstReplacement() == null || end > lastStart || end > sb.length()) {
                continue; // нет замены или находки пересекаются
            }
            sb.replace(m.offset(), end, m.firstReplacement());
            lastStart = m.offset();
        }
        return sb.toString();
    }

    private static String hint(LtMatch m) {
        String message = m.message() == null ? "" : m.message().strip();
        String replacement = m.firstReplacement();
        if (replacement == null) {
            return message;
        }
        return message.isEmpty() ? "→ " + replacement : message + " → " + replacement;
    }

    /** Фрагмент встречается целыми словами: "go" не считается найденным внутри "gone". */
    private static boolean containsWords(String text, String span) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(span) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static boolean overlaps(String a, String b) {
        return a.contains(b) || b.contains(a);
    }

    public static boolean sameText(String a, String b) {
        return a != null && b != null && normalize(a).equals(normalize(b));
    }

    /** Сравнение без учёта регистра, пробелов и знаков препинания по краям. */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\p{Punct}]+|[\\s\\p{Punct}]+$", "");
    }
}
