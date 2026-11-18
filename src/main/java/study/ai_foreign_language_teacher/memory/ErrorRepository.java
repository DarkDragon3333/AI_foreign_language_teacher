package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.util.List;

public interface ErrorRepository {

    /** Сырой вывод LanguageTool по реплике ученика. */
    void saveMatches(long messageId, List<LtMatch> matches);

    /** +1 к счётчику правила (журнал ошибок; на шаге 4 по нему строится повторение). */
    void record(long userId, String ruleId, String source, String category, String title, String span);

    /** Самые частые ошибки, сначала недавние и частые. */
    List<ErrorEvent> top(long userId, int limit);

    List<SessionSummary.RuleHit> topRulesInSession(long sessionId, int limit);
}
