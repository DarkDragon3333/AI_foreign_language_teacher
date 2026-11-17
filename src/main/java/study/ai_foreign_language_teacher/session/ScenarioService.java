package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.RoleScenario;
import study.ai_foreign_language_teacher.domain.ScenarioGoal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Прогресс по целям сценария в памяти процесса (достаточно для одного ученика на машине). */
@Service
public class ScenarioService {

    private final ScenarioCatalog catalog;
    private final ConcurrentHashMap<Long, SessionProgress> progress = new ConcurrentHashMap<>();

    public ScenarioService(ScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    public List<RoleScenario> list() {
        return catalog.all();
    }

    public void findForStart(String slug) {
        catalog.find(slug).orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + slug));
    }

    public Optional<RoleScenario> promptContext(LessonSession session) {
        return scenarioOf(session);
    }

    public Optional<RoleScenario> scenarioOf(LessonSession session) {
        if (session == null || session.mode() != LessonMode.ROLEPLAY) {
            return Optional.empty();
        }
        return catalog.find(session.topic());
    }

    public void start(long sessionId, String slug) {
        RoleScenario scenario = catalog.find(slug).orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + slug));
        progress.put(sessionId, new SessionProgress(scenario.slug(), new LinkedHashSet<>()));
    }

    public void clear(long sessionId) {
        progress.remove(sessionId);
    }

    /** Отмечает цели по подсказкам в реплике ученика. Возвращает id новых выполненных целей. */
    public List<String> onUserMessage(long sessionId, LessonSession session, String userText) {
        Optional<RoleScenario> scenario = scenarioOf(session);
        if (scenario.isEmpty() || userText == null || userText.isBlank()) {
            return List.of();
        }
        SessionProgress state = progress.computeIfAbsent(sessionId,
                id -> new SessionProgress(scenario.get().slug(), new LinkedHashSet<>()));
        String lower = userText.toLowerCase(Locale.ROOT);
        List<String> fresh = new java.util.ArrayList<>();
        for (ScenarioGoal goal : scenario.get().goals()) {
            if (state.done.contains(goal.id())) {
                continue;
            }
            if (matches(lower, goal)) {
                state.done.add(goal.id());
                fresh.add(goal.id());
            }
        }
        return List.copyOf(fresh);
    }

    public Optional<Progress> progress(long sessionId, LessonSession session) {
        Optional<RoleScenario> scenario = scenarioOf(session);
        if (scenario.isEmpty()) {
            return Optional.empty();
        }
        SessionProgress state = progress.computeIfAbsent(sessionId,
                id -> new SessionProgress(scenario.get().slug(), new LinkedHashSet<>()));
        if (!state.slug.equals(scenario.get().slug())) {
            state = new SessionProgress(scenario.get().slug(), new LinkedHashSet<>());
            progress.put(sessionId, state);
        }
        return Optional.of(Progress.of(scenario.get(), state.done));
    }

    public Set<String> completedGoals(long sessionId, LessonSession session) {
        Optional<RoleScenario> scenario = scenarioOf(session);
        if (scenario.isEmpty()) {
            return Set.of();
        }
        SessionProgress state = progress.get(sessionId);
        if (state == null || !state.slug.equals(scenario.get().slug())) {
            return Set.of();
        }
        return Set.copyOf(state.done);
    }

    private static boolean matches(String lower, ScenarioGoal goal) {
        for (String hint : goal.hints()) {
            String h = hint.toLowerCase(Locale.ROOT).trim();
            if (h.equals("?") && lower.contains("?")) {
                return true;
            }
            if (!h.equals("?") && lower.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static final class SessionProgress {
        private final String slug;
        private final Set<String> done;

        private SessionProgress(String slug, Set<String> done) {
            this.slug = slug;
            this.done = done;
        }
    }

    public record Progress(String slug, String titleRu, String title, List<GoalState> goals, int doneCount, int total) {

        static Progress of(RoleScenario scenario, Set<String> done) {
            List<GoalState> goals = scenario.goals().stream()
                    .map(g -> new GoalState(g.id(), g.textRu(), g.textEn(), done.contains(g.id())))
                    .toList();
            return new Progress(scenario.slug(), scenario.titleRu(), scenario.title(), goals, done.size(), goals.size());
        }
    }

    public record GoalState(String id, String textRu, String textEn, boolean done) {
    }
}