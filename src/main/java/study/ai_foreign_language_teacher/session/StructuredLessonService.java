package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonPhase;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.StructuredPlanState;
import study.ai_foreign_language_teacher.memory.MessageRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class StructuredLessonService {

    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final Clock clock;

    public StructuredLessonService(SessionRepository sessions, MessageRepository messages, Clock clock) {
        this.sessions = sessions;
        this.messages = messages;
        this.clock = clock;
    }

    public record PhaseView(String lessonPhase, String phaseLabel, int minutesRemaining, int targetMinutes) {
        static PhaseView of(LessonSession session, LessonPhase phase, Instant now) {
            StructuredPlanState plan = session.planState();
            if (plan == null) {
                return null;
            }
            return new PhaseView(
                    phase.json(),
                    phase.label(),
                    StructuredLessonPhases.minutesRemaining(plan.targetMinutes(), session.startedAt(), now),
                    plan.targetMinutes());
        }
    }

    public StructuredPlanState initPlan(int targetMinutes, Instant startedAt) {
        int normalized = StructuredLessonPhases.normalizeDuration(targetMinutes);
        if (!StructuredLessonPhases.isSupportedDuration(normalized)) {
            throw new IllegalArgumentException("durationMin must be 15 or 25");
        }
        return StructuredPlanState.start(normalized, startedAt);
    }

    public Optional<PhaseView> view(LessonSession session) {
        if (session.mode() != LessonMode.STRUCTURED || session.planState() == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        LessonPhase phase = session.planState().phase();
        return Optional.of(PhaseView.of(session, phase, now));
    }

    /**
     * Пересчитывает фазу по времени и сохраняет при смене (не раньше одной реплики ученика в текущей фазе).
     */
    public LessonSession syncPhase(LessonSession session) {
        if (session.mode() != LessonMode.STRUCTURED || session.planState() == null || session.endedAt() != null) {
            return session;
        }
        StructuredPlanState plan = session.planState();
        Instant now = clock.instant();
        long elapsed = StructuredLessonPhases.elapsedSeconds(session.startedAt(), now);
        LessonPhase timePhase = StructuredLessonPhases.phaseAt(plan.targetMinutes(), elapsed);
        LessonPhase stored = plan.phase();
        if (!timePhase.isBefore(stored) && timePhase != stored) {
            int userTurns = messages.countUserTurnsSince(session.id(), plan.phaseStartedAt());
            if (userTurns >= 1 || timePhase == LessonPhase.FEEDBACK) {
                StructuredPlanState next = plan.withPhase(timePhase, now);
                sessions.updatePlanState(session.id(), next);
                return sessions.find(session.id()).orElse(session.withPlanState(next));
            }
        }
        return session;
    }
}