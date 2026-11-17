package study.ai_foreign_language_teacher.domain;

import java.util.List;

/**
 * Проверенная заметка по одному правилу. Модель объясняет по ней, а не по памяти.
 * Формулировки свои: правило простыми словами, верный пример, неверный, типичная ошибка русскоязычных.
 */
public record RuleCard(
        String slug,
        String cefrMin,
        String cefrMax,
        String title,
        String titleRu,
        String rule,
        String ruleRu,
        String exampleOk,
        String exampleBad,
        String l1Trap,
        List<String> ltRules,
        List<String> keywords) {

    public RuleCard {
        ltRules = ltRules == null ? List.of() : List.copyOf(ltRules);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /** Текст, который превращается в числа для поиска по смыслу. */
    public String passage() {
        return title + ". " + rule + " Correct: " + exampleOk + " Wrong: " + exampleBad + ". "
                + titleRu + ". " + ruleRu + " " + l1Trap;
    }

    /** Всё, от чего зависит эмбеддинг: при правке текста вектор пересчитывается. */
    public String hashSource() {
        return passage() + "\n" + String.join("\n", keywords);
    }
}
