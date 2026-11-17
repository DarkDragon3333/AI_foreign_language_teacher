package study.ai_foreign_language_teacher.memory.rules;

import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Выбирает одну-две карточки к реплике.
 * Сначала точное совпадение с правилом LanguageTool, иначе слова («have been», «yesterday»)
 * и, если CPU-модель уже посчитала векторы, близость по смыслу.
 * В обычную правильную реплику карточка не подкладывается: иначе модель начинает «исправлять» то, что верно.
 */
@Component
public class RuleRetriever {

    static final int LIMIT = 2;
    /** Одно слово вроде yesterday даёт до 8, словосочетание have been — 12. Ниже этого карточку не берём. */
    static final int LEXICAL_BAR = 8;
    /** Без общих слов карточка берётся только если смысл очень близкий. */
    static final double COSINE_BAR = 0.72;

    private final CardCatalog catalog;
    private final QueryEmbedder embeddings;

    public RuleRetriever(CardCatalog catalog, QueryEmbedder embeddings) {
        this.catalog = catalog;
        this.embeddings = embeddings;
    }

    public List<RuleCard> retrieve(Cefr level, LessonMode mode, String topic, String userText,
                                   List<LtMatch> checker, boolean stuck) {
        String text = userText == null ? "" : userText;
        List<LtMatch> grammar = grammar(checker);
        boolean loose = stuck || mode == LessonMode.EXPLAIN || grammarQuestion(text) || !grammar.isEmpty();
        if (!loose) {
            return List.of();
        }

        StringBuilder blob = new StringBuilder(text);
        for (LtMatch match : grammar) {
            blob.append(' ').append(nullToEmpty(match.message()))
                    .append(' ').append(nullToEmpty(match.span()))
                    .append(' ').append(nullToEmpty(match.title()));
        }
        if (mode == LessonMode.EXPLAIN && topic != null) {
            blob.append(' ').append(topic);
        }
        String haystack = blob.toString();
        float[] query = embeddings.embedQuery(haystack);

        List<Hit> hits = new ArrayList<>();
        for (RuleCard card : catalog.all()) {
            boolean exact = false;
            for (LtMatch match : grammar) {
                if (catalog.byLtRule(match.ruleId()).contains(card)) {
                    exact = true;
                    break;
                }
            }
            int lexical = lexical(card, haystack);
            double cosine = cosine(query, catalog.vector(card.slug()));
            if (!exact && lexical < LEXICAL_BAR && cosine < COSINE_BAR) {
                continue;
            }
            if (!exact && aboveLevel(card, level)) {
                lexical = lexical / 4;
                if (lexical < LEXICAL_BAR && cosine < COSINE_BAR) {
                    continue;
                }
            }
            double score = (exact ? 100 : 0) + lexical + cosine * 30;
            hits.add(new Hit(card, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        List<RuleCard> picked = new ArrayList<>();
        for (Hit hit : hits) {
            if (picked.size() == LIMIT) {
                break;
            }
            picked.add(hit.card());
        }
        return List.copyOf(picked);
    }

    static int lexical(RuleCard card, String haystack) {
        String hay = haystack.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : card.keywords()) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String term = keyword.toLowerCase(Locale.ROOT).strip();
            if (term.length() < 3 || !containsTerm(hay, term)) {
                continue;
            }
            score += term.contains(" ") ? 12 : Math.min(8, term.length());
        }
        String title = card.title().toLowerCase(Locale.ROOT);
        if (title.length() >= 6 && containsTerm(hay, title)) {
            score += 12;
        }
        return score;
    }

    public static boolean grammarQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("when do i") || t.contains("when to use") || t.contains("how do i use")
                || t.contains("how to use") || t.contains("difference between")
                || t.contains("what's the difference") || t.contains("what is the difference")
                || t.contains("which tense") || t.contains("explain the") || t.contains("explain when")
                || t.contains("когда ") || t.contains("как сказать") || t.contains("как будет")
                || t.contains("как правильно") || t.contains("в чём разница") || t.contains("в чем разница")
                || t.contains("почему нельзя");
    }

    private static boolean aboveLevel(RuleCard card, Cefr level) {
        return Cefr.parse(card.cefrMin()).ordinal() > level.ordinal() + 1;
    }

    private static List<LtMatch> grammar(List<LtMatch> checker) {
        if (checker == null || checker.isEmpty()) {
            return List.of();
        }
        return checker.stream().filter(m -> !m.spelling()).toList();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static boolean containsTerm(String haystack, String term) {
        int from = 0;
        while (from <= haystack.length() - term.length()) {
            int at = haystack.indexOf(term, from);
            if (at < 0) {
                return false;
            }
            boolean left = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + term.length();
            boolean right = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (left && right) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * (double) b[i];
            na += a[i] * (double) a[i];
            nb += b[i] * (double) b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Hit(RuleCard card, double score) {
    }
}
