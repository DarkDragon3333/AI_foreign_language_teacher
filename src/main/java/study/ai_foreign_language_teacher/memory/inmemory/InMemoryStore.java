package study.ai_foreign_language_teacher.memory.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.domain.SessionPlanState;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.MessageRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.memory.StorageHealth;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Хранилище в памяти для профиля "memory" (разработка и тесты без Postgres). */
@Repository
@Profile("memory")
public class InMemoryStore implements ProfileRepository, SessionRepository, MessageRepository, ErrorRepository, StorageHealth {

    private final TutorProperties props;
    private final AtomicLong sessionSeq = new AtomicLong();
    private final AtomicLong messageSeq = new AtomicLong();
    private final Map<Long, LessonSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, List<ChatMessage>> messages = new ConcurrentHashMap<>();
    private final Map<Long, List<LtMatch>> ltMatches = new ConcurrentHashMap<>();
    private final Map<String, ErrorEvent> errors = new ConcurrentHashMap<>();
    private volatile UserProfile profile;

    public InMemoryStore(TutorProperties props) {
        this.props = props;
    }

    @Override
    public synchronized UserProfile getOrCreateDefault() {
        if (profile == null) {
            TutorProperties.Lesson l = props.lesson();
            profile = new UserProfile(1, "Student", l.nativeLang(), l.targetLang(),
                    Cefr.parseOr(l.defaultCefr(), Cefr.B1), false, UserProfile.DEFAULT_DAILY_GOAL_MIN,
                    UserProfile.DEFAULT_STUDY_WEEKDAYS, null);
        }
        return profile;
    }

    @Override
    public synchronized UserProfile save(UserProfile updated) {
        profile = updated;
        return profile;
    }

    @Override
    public LessonSession create(long userId, LessonMode mode, String topic, Cefr cefr, String model,
                                SessionPlanState planState) {
        LessonSession s = new LessonSession(sessionSeq.incrementAndGet(), userId, mode, topic, cefr, 0, Instant.now(),
                null, model, null, planState);
        sessions.put(s.id(), s);
        return s;
    }

    @Override
    public void updatePlanState(long id, SessionPlanState planState) {
        sessions.computeIfPresent(id, (k, s) -> s.withPlanState(planState));
    }

    @Override
    public Optional<LessonSession> find(long id) {
        return Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<LessonSession> recent(long userId, int limit) {
        return sessions.values().stream()
                .filter(s -> s.userId() == userId)
                .sorted(Comparator.comparingLong(LessonSession::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void updateSupportLevel(long id, int supportLevel) {
        sessions.computeIfPresent(id, (k, s) -> new LessonSession(s.id(), s.userId(), s.mode(), s.topic(), s.cefr(),
                supportLevel, s.startedAt(), s.endedAt(), s.model(), s.summary(), s.planState()));
    }

    @Override
    public void updateCefr(long id, Cefr cefr) {
        sessions.computeIfPresent(id, (k, s) -> new LessonSession(s.id(), s.userId(), s.mode(), s.topic(), cefr,
                s.supportLevel(), s.startedAt(), s.endedAt(), s.model(), s.summary(), s.planState()));
    }

    @Override
    public void end(long id, SessionSummary summary) {
        sessions.computeIfPresent(id, (k, s) -> new LessonSession(s.id(), s.userId(), s.mode(), s.topic(), s.cefr(),
                s.supportLevel(), s.startedAt(), Instant.now(), s.model(), summary, s.planState()));
    }

    @Override
    public Optional<LessonSession> findOpen(long userId) {
        return sessions.values().stream()
                .filter(s -> s.userId() == userId && s.endedAt() == null)
                .max(Comparator.comparingLong(LessonSession::id));
    }

    @Override
    public int practicedMinutesOnDay(long userId, LocalDate day) {
        ZoneId zone = ZoneId.systemDefault();
        return sessions.values().stream()
                .filter(s -> s.userId() == userId && s.endedAt() != null && s.summary() != null)
                .filter(s -> LocalDate.ofInstant(s.startedAt(), zone).equals(day))
                .mapToInt(s -> s.summary().durationMinutes())
                .sum();
    }

    @Override
    public ChatMessage add(long sessionId, Role role, String text, String payloadJson) {
        ChatMessage m = new ChatMessage(messageSeq.incrementAndGet(), sessionId, role, text, payloadJson, Instant.now());
        List<ChatMessage> list = messages.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(m);
        }
        return m;
    }

    @Override
    public List<ChatMessage> lastN(long sessionId, int n) {
        List<ChatMessage> all = all(sessionId);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    @Override
    public int countUserTurnsSince(long sessionId, Instant since) {
        if (since == null) {
            return countUserTurns(sessionId);
        }
        return (int) all(sessionId).stream()
                .filter(m -> m.role() == Role.USER && !m.createdAt().isBefore(since))
                .count();
    }

    @Override
    public int countUserTurns(long sessionId) {
        return (int) all(sessionId).stream().filter(m -> m.role() == Role.USER).count();
    }

    @Override
    public List<ChatMessage> all(long sessionId) {
        List<ChatMessage> list = messages.get(sessionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    @Override
    public void saveMatches(long messageId, List<LtMatch> matches) {
        if (!matches.isEmpty()) {
            ltMatches.put(messageId, List.copyOf(matches));
        }
    }

    @Override
    public void record(long userId, String ruleId, String source, String category, String title, String span) {
        errors.merge(userId + ":" + ruleId,
                new ErrorEvent(ruleId, source, category, title, span, 1, Instant.now()),
                (old, fresh) -> new ErrorEvent(ruleId, source, category, title, span, old.count() + 1, fresh.lastSeen()));
    }

    @Override
    public List<SessionSummary.RuleHit> topRulesInSession(long sessionId, int limit) {
        java.util.Map<String, SessionSummary.RuleHit> counts = new java.util.HashMap<>();
        for (ChatMessage m : all(sessionId)) {
            List<LtMatch> matches = ltMatches.getOrDefault(m.id(), List.of());
            for (LtMatch match : matches) {
                String title = match.title().isBlank() ? match.ruleId() : match.title();
                counts.merge(match.ruleId(),
                        new SessionSummary.RuleHit(match.ruleId(), title, 1),
                        (a, b) -> new SessionSummary.RuleHit(a.ruleId(), a.title(), a.count() + 1));
            }
        }
        return counts.values().stream()
                .sorted(Comparator.comparingInt(SessionSummary.RuleHit::count).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<ErrorEvent> top(long userId, int limit) {
        String prefix = userId + ":";
        return errors.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingInt(ErrorEvent::count).reversed()
                        .thenComparing(ErrorEvent::lastSeen, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public String kind() {
        return "memory";
    }
}
