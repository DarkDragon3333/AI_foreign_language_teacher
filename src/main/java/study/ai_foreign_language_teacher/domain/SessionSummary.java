package study.ai_foreign_language_teacher.domain;

import java.util.List;

/** Сводка урока при завершении (цифры для истории и разбора плана). */
public record SessionSummary(
        int durationMinutes,
        int userTurns,
        List<RuleHit> topRules) {

    public record RuleHit(String ruleId, String title, int count) {
    }
}