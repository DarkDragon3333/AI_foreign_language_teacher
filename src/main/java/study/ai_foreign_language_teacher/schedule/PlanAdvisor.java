package study.ai_foreign_language_teacher.schedule;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.ScheduleSlot;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.llm.LlmPlanRevision;
import study.ai_foreign_language_teacher.llm.PlanRevisionSchema;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PlanAdvisor {

    private final LlmClient llm;
    private final ObjectMapper mapper;

    public PlanAdvisor(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public record SlotRevision(String date, String activityType, String reason) {
    }

    public record PlanRevision(String summary, String source, List<SlotRevision> revisions) {
    }

    public PlanRevision suggest(UserProfile profile, InsightsService.InsightsBundle stats, int reviewDue,
                                List<ScheduleSlot> future) {
        PlanRevision fallback = ruleBased(stats, reviewDue, future);
        if (llm.status().up()) {
            try {
                String prompt = buildPrompt(profile, stats, reviewDue, future);
                String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.3, PlanRevisionSchema.schema(), null);
                LlmPlanRevision parsed = mapper.readValue(raw, LlmPlanRevision.class);
                if (parsed.revisions() != null && !parsed.revisions().isEmpty()) {
                    List<SlotRevision> revs = parsed.revisions().stream()
                            .map(r -> new SlotRevision(r.date(), r.activityType(), r.reason()))
                            .toList();
                    return new PlanRevision(
                            blankTo(parsed.summary(), fallback.summary()),
                            "model",
                            sanitize(revs, future));
                }
            } catch (RuntimeException ignored) {
                /* fall back */
            }
        }
        return fallback;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private List<SlotRevision> sanitize(List<SlotRevision> revisions, List<ScheduleSlot> future) {
        List<String> allowedDates = future.stream().map(s -> s.slotDate().toString()).toList();
        List<SlotRevision> out = new ArrayList<>();
        for (SlotRevision r : revisions) {
            if (r.date() == null || !allowedDates.contains(r.date())) {
                continue;
            }
            try {
                ActivityType.parse(r.activityType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            out.add(new SlotRevision(r.date(), ActivityType.parse(r.activityType()).json(),
                    r.reason() == null ? "" : r.reason().strip()));
        }
        return out;
    }

    private String buildPrompt(UserProfile profile, InsightsService.InsightsBundle stats, int reviewDue,
                               List<ScheduleSlot> future) {
        StringBuilder sb = new StringBuilder();
        sb.append("You adjust an English study plan. Learner CEFR: ").append(profile.cefr()).append(".\n");
        sb.append("Last ").append(stats.lessonCount()).append(" lessons, ").append(stats.totalMinutes())
                .append(" minutes total.\n");
        sb.append("Modes (minutes): ").append(stats.modeMinutes()).append("\n");
        sb.append("Frequent mistakes: ").append(stats.frequentRules()).append("\n");
        sb.append("FSRS cards due today: ").append(reviewDue).append("\n");
        sb.append("Future planned slots (date, current type):\n");
        for (ScheduleSlot s : future) {
            sb.append("- ").append(s.slotDate()).append(" ").append(s.activityType().json()).append("\n");
        }
        sb.append("""
                Return JSON only. activity_type must be one of: review, grammar, speaking, listening, conversation.
                Change only slots that need it. summary is one short sentence in Russian for the learner.
                """);
        return sb.toString();
    }

    PlanRevision ruleBased(InsightsService.InsightsBundle stats, int reviewDue, List<ScheduleSlot> future) {
        List<SlotRevision> revisions = new ArrayList<>();
        ActivityType preferred;
        String reason;
        if (reviewDue > 0) {
            preferred = ActivityType.REVIEW;
            reason = "Cards due for review";
        } else if (!stats.frequentRules().isEmpty()) {
            preferred = ActivityType.GRAMMAR;
            reason = "Frequent grammar mistakes";
        } else if (stats.modeMinutes().getOrDefault("conversation", 0) < stats.totalMinutes() / 2) {
            preferred = ActivityType.CONVERSATION;
            reason = "More speaking practice";
        } else {
            preferred = ActivityType.LISTENING;
            reason = "Balance with listening";
        }
        for (ScheduleSlot s : future) {
            if (s.activityType() != preferred) {
                revisions.add(new SlotRevision(s.slotDate().toString(), preferred.json(), reason));
            }
        }
        String summary = "План обновлён правилами: акцент — " + preferred.json() + ".";
        return new PlanRevision(summary, "rules", revisions);
    }
}