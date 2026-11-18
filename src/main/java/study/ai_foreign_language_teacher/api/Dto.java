package study.ai_foreign_language_teacher.api;

import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;

import java.time.Instant;
import java.util.List;

/** Формы запросов и ответов REST API. */
public final class Dto {

    private Dto() {
    }

    public record StartSessionRequest(String cefr, String topic, String mode, String model) {
    }

    public record ChatRequest(Long sessionId, String text, Boolean hint) {
    }

    public record LevelRequest(String cefr) {
    }

    public record ProfileRequest(String displayName, String cefr, Boolean voiceEnabled, Integer dailyGoalMin,
                                 java.util.List<Integer> studyWeekdays) {
    }

    public record SessionSummaryView(int durationMinutes, int userTurns, List<RuleHitView> topRules) {
        static SessionSummaryView of(SessionSummary s) {
            if (s == null) {
                return null;
            }
            return new SessionSummaryView(s.durationMinutes(), s.userTurns(),
                    s.topRules().stream().map(RuleHitView::of).toList());
        }
    }

    public record RuleHitView(String ruleId, String title, int count) {
        static RuleHitView of(SessionSummary.RuleHit h) {
            return new RuleHitView(h.ruleId(), h.title(), h.count());
        }
    }

    public record SessionView(long id, String mode, String topic, String cefr, int supportLevel,
                              Instant startedAt, Instant endedAt, String model, SessionSummaryView summary) {
        static SessionView of(LessonSession s) {
            return new SessionView(s.id(), s.mode().json(), s.topic(), s.cefr().name(), s.supportLevel(),
                    s.startedAt(), s.endedAt(), s.model(), SessionSummaryView.of(s.summary()));
        }
    }

    public record DailyProgressView(int minutesDone, int goalMinutes, boolean goalMet) {
        static DailyProgressView of(study.ai_foreign_language_teacher.session.LessonService.DailyProgress p) {
            return new DailyProgressView(p.minutesDone(), p.goalMinutes(), p.goalMet());
        }
    }

    /** reply заполнен только у реплик учителя. */
    public record MessageView(long id, String role, String text, TeacherReply reply, Instant createdAt) {
        static MessageView of(ChatMessage m, TeacherReply reply) {
            return new MessageView(m.id(), m.role() == Role.USER ? "user" : "teacher", m.text(), reply, m.createdAt());
        }
    }

    public record ProfileView(long id, String displayName, String nativeLang, String targetLang, String cefr,
                              boolean voiceEnabled, int dailyGoalMin, List<Integer> dailyGoalChoices,
                              List<Integer> studyWeekdays, List<String> levels, String model, String note) {
        static ProfileView of(UserProfile p, String model) {
            String note = switch (p.cefr()) {
                case C1, C2 -> "На уровнях C1–C2 стиль небольшой локальной модели упрощён: нюансы и идиомы она передаёт хуже, чем живой преподаватель.";
                default -> null;
            };
            return new ProfileView(p.id(), p.displayName(), p.nativeLang(), p.targetLang(), p.cefr().name(),
                    p.voiceEnabled(), p.dailyGoalMin(), List.of(5, 10, 15, 25), p.studyWeekdays(),
                    List.of("A1", "A2", "B1", "B2", "C1", "C2"), model, note);
        }
    }

    public record ScheduleWeekView(String weekStart, List<Integer> studyWeekdays, List<ScheduleSlotView> slots) {
        static ScheduleWeekView of(study.ai_foreign_language_teacher.schedule.ScheduleService.WeekView w) {
            return new ScheduleWeekView(w.weekStart().toString(), w.studyWeekdays(),
                    w.slots().stream().map(ScheduleSlotView::of).toList());
        }
    }

    public record ScheduleSlotView(String date, String activityType, String status, Long sessionId, String planNote) {
        static ScheduleSlotView of(study.ai_foreign_language_teacher.schedule.ScheduleService.SlotView s) {
            return new ScheduleSlotView(s.date().toString(), s.activityType(), s.status(), s.sessionId(), s.planNote());
        }
    }

    public record StudyWeekdaysRequest(List<Integer> days) {
    }

    public record ScheduleSlotPatch(String activityType, String status) {
    }

    public record InsightsView(int lessonCount, int totalMinutes, java.util.Map<String, Integer> modeMinutes,
                               List<String> frequentRules, List<LessonInsightView> lessons) {
        static InsightsView of(study.ai_foreign_language_teacher.schedule.InsightsService.InsightsBundle b) {
            return new InsightsView(b.lessonCount(), b.totalMinutes(), b.modeMinutes(), b.frequentRules(),
                    b.lessons().stream().map(LessonInsightView::of).toList());
        }
    }

    public record LessonInsightView(long sessionId, String mode, int minutes, int userTurns, List<String> topRules) {
        static LessonInsightView of(study.ai_foreign_language_teacher.schedule.InsightsService.LessonInsight i) {
            return new LessonInsightView(i.sessionId(), i.mode(), i.minutes(), i.userTurns(), i.topRules());
        }
    }

    public record PlanRevisionView(String summary, String source, List<PlanSlotRevisionView> revisions) {
        static PlanRevisionView of(study.ai_foreign_language_teacher.schedule.PlanAdvisor.PlanRevision r) {
            return new PlanRevisionView(r.summary(), r.source(),
                    r.revisions().stream().map(PlanSlotRevisionView::of).toList());
        }
    }

    public record PlanSlotRevisionView(String date, String activityType, String reason) {
        static PlanSlotRevisionView of(study.ai_foreign_language_teacher.schedule.PlanAdvisor.SlotRevision r) {
            return new PlanSlotRevisionView(r.date(), r.activityType(), r.reason());
        }
    }

    public record JournalEntry(String ruleId, String source, String category, String title, String example,
                               int count, Instant lastSeen) {
        static JournalEntry of(ErrorEvent e) {
            return new JournalEntry(e.ruleId(), e.source(), e.category(), e.title(), e.sampleSpan(), e.count(), e.lastSeen());
        }
    }

    public record ReviewCard(String slug, String title, String titleRu, String cefr, String rule, String ruleRu,
                             String exampleOk, String exampleBad, String trap) {
        static ReviewCard of(RuleCard card) {
            String cefr = card.cefrMin().equals(card.cefrMax()) ? card.cefrMin() : card.cefrMin() + "–" + card.cefrMax();
            return new ReviewCard(card.slug(), card.title(), card.titleRu(), cefr, card.rule(), card.ruleRu(),
                    card.exampleOk(), card.exampleBad(), card.l1Trap());
        }
    }

    public record ReviewQueue(int due, java.util.List<ReviewCard> cards) {
    }

    public record RateRequest(int rating) {
    }

    public record RateResult(String slug, Instant due, long intervalDays, int remaining) {
    }

    public record ErrorView(String error) {
    }

    public record GrammarStartRequest(List<String> types, Boolean random) {
    }

    public record GrammarStartView(long runId, GrammarExerciseView current) {
        static GrammarStartView of(study.ai_foreign_language_teacher.practice.GrammarPracticeService.StartResult r) {
            return new GrammarStartView(r.runId(), GrammarExerciseView.of(r.current()));
        }
    }

    public record GrammarExerciseView(int index, int total, String type, String prompt, List<String> choices, String rule) {
        static GrammarExerciseView of(study.ai_foreign_language_teacher.practice.GrammarPracticeService.ExerciseView v) {
            if (v == null) {
                return null;
            }
            return new GrammarExerciseView(v.index(), v.total(), v.type(), v.prompt(), v.choices(), v.rule());
        }
    }

    public record GrammarAnswerRequest(String answer) {
    }

    public record GrammarAnswerView(boolean correct, String expected, GrammarExerciseView next, boolean finished) {
        static GrammarAnswerView of(study.ai_foreign_language_teacher.practice.GrammarPracticeService.AnswerResult r) {
            return new GrammarAnswerView(r.correct(), r.expected(), GrammarExerciseView.of(r.next()), r.finished());
        }
    }

    public record ScenarioListItem(String slug, String title, String titleRu, String cefrMin, String cefrMax,
                                   int goals) {
        static ScenarioListItem of(study.ai_foreign_language_teacher.domain.RoleScenario s) {
            return new ScenarioListItem(s.slug(), s.title(), s.titleRu(), s.cefrMin(), s.cefrMax(), s.goals().size());
        }
    }

    public record ScenarioGoalView(String id, String textRu, String textEn, boolean done) {
        static ScenarioGoalView of(study.ai_foreign_language_teacher.session.ScenarioService.GoalState g) {
            return new ScenarioGoalView(g.id(), g.textRu(), g.textEn(), g.done());
        }
    }

    public record ScenarioProgressView(String slug, String titleRu, String title, int done, int total,
                                       java.util.List<ScenarioGoalView> goals) {
        static ScenarioProgressView of(study.ai_foreign_language_teacher.session.ScenarioService.Progress p) {
            return new ScenarioProgressView(p.slug(), p.titleRu(), p.title(), p.doneCount(), p.total(),
                    p.goals().stream().map(ScenarioGoalView::of).toList());
        }
    }
}
