package study.ai_foreign_language_teacher.session;

import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.MessageRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class SessionSummaries {

    private SessionSummaries() {
    }

    public static int durationMinutes(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return 0;
        }
        long seconds = Duration.between(startedAt, endedAt).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) Math.max(1, (seconds + 59) / 60);
    }

    public static SessionSummary build(LessonSession session, Instant endedAt, MessageRepository messages,
                                     ErrorRepository errors) {
        int minutes = durationMinutes(session.startedAt(), endedAt);
        int turns = messages.countUserTurns(session.id());
        List<SessionSummary.RuleHit> rules = errors.topRulesInSession(session.id(), 5);
        return new SessionSummary(minutes, turns, rules);
    }
}