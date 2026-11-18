package study.ai_foreign_language_teacher.schedule;

import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InsightsService {

    private InsightsService() {
    }

    public record LessonInsight(long sessionId, String mode, int minutes, int userTurns, List<String> topRules) {
    }

    public record InsightsBundle(int lessonCount, int totalMinutes, Map<String, Integer> modeMinutes,
                                 List<String> frequentRules, List<LessonInsight> lessons) {
    }

    public static InsightsBundle collect(SessionRepository sessions, ErrorRepository errors, long userId, int limit) {
        List<LessonSession> recent = sessions.recent(userId, limit).stream()
                .filter(s -> s.endedAt() != null && s.summary() != null)
                .toList();
        int totalMinutes = 0;
        Map<String, Integer> modeMinutes = new HashMap<>();
        List<LessonInsight> lessons = new ArrayList<>();
        for (LessonSession s : recent) {
            SessionSummary sum = s.summary();
            totalMinutes += sum.durationMinutes();
            modeMinutes.merge(s.mode().json(), sum.durationMinutes(), Integer::sum);
            List<String> rules = sum.topRules().stream()
                    .map(r -> r.title() + " (" + r.count() + ")")
                    .toList();
            lessons.add(new LessonInsight(s.id(), s.mode().json(), sum.durationMinutes(), sum.userTurns(), rules));
        }
        List<String> frequentRules = errors.top(userId, 5).stream()
                .map(e -> (e.title() != null && !e.title().isBlank() ? e.title() : e.ruleId()) + " ×" + e.count())
                .toList();
        return new InsightsBundle(lessons.size(), totalMinutes, modeMinutes, frequentRules, lessons);
    }
}