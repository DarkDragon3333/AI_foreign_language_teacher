package study.ai_foreign_language_teacher.api;

import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.DailyGoalMinutes;
import study.ai_foreign_language_teacher.domain.LearningPlan;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.session.PlacementLessonService;

import java.time.Instant;
import java.util.List;

/** Формы запросов и ответов REST API. */
public final class Dto {

    private Dto() {
    }

    public record StartSessionRequest(String cefr, String topic, String mode, String model, Integer durationMin) {
    }

    public record ChatRequest(Long sessionId, String text, Boolean hint) {
    }

    public record LevelRequest(String cefr) {
    }

    public record ProfileRequest(String displayName, String cefr, Boolean voiceEnabled, Integer dailyGoalMin,
                                 java.util.List<Integer> studyWeekdays, String targetEndDate) {
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

    public record LearningPlanView(List<Integer> studyWeekdays, int dailyGoalMin, List<LearningPlanSlotView> monthSlots,
                                   String targetEndDate) {
        static LearningPlanView of(LearningPlan plan) {
            if (plan == null) {
                return null;
            }
            List<LearningPlanSlotView> slots = plan.monthSlots() == null ? List.of()
                    : plan.monthSlots().stream().map(LearningPlanSlotView::of).toList();
            return new LearningPlanView(plan.studyWeekdays(), plan.dailyGoalMin(), slots, plan.targetEndDate());
        }
    }

    public record LearningPlanSlotView(String date, String activityType) {
        static LearningPlanSlotView of(LearningPlan.MonthSlot slot) {
            return new LearningPlanSlotView(slot.date(), slot.activityType());
        }
    }

    public record ApplyLearningPlanRequest(List<Integer> studyWeekdays, Integer dailyGoalMin,
                                           List<LearningPlanSlotView> monthSlots, String targetEndDate) {
        public LearningPlan toPlan() {
            List<LearningPlan.MonthSlot> slots = monthSlots == null ? List.of()
                    : monthSlots.stream().map(s -> new LearningPlan.MonthSlot(s.date(), s.activityType())).toList();
            int goal = dailyGoalMin == null ? 15 : DailyGoalMinutes.normalize(dailyGoalMin);
            List<Integer> days = studyWeekdays == null ? List.of() : studyWeekdays;
            String end = targetEndDate == null || targetEndDate.isBlank() ? null : targetEndDate.strip();
            return new LearningPlan(days, goal, slots, end);
        }
    }

    public record SessionView(long id, String mode, String topic, String cefr, int supportLevel,
                              Instant startedAt, Instant endedAt, String model, SessionSummaryView summary,
                              String lessonPhase, String phaseLabel, Integer minutesRemaining, Integer targetMinutes,
                              String placementStage, String placementStepLabel, Integer placementStepIndex,
                              Integer placementStepTotal, Boolean placementPlanReady, String placementAssessedLevel,
                              LearningPlanView placementPlan) {
        static SessionView of(LessonSession s) {
            return of(s, (LessonService.SessionExtras) null);
        }

        static SessionView of(LessonSession s, LessonService.SessionExtras extras) {
            String lessonPhase = null;
            String phaseLabel = null;
            Integer minutesRemaining = null;
            Integer targetMinutes = null;
            if (extras != null && extras.structured() != null) {
                var phase = extras.structured();
                lessonPhase = phase.lessonPhase();
                phaseLabel = phase.phaseLabel();
                minutesRemaining = phase.minutesRemaining();
                targetMinutes = phase.targetMinutes();
            }
            String placementStage = null;
            String placementStepLabel = null;
            Integer placementStepIndex = null;
            Integer placementStepTotal = null;
            Boolean placementPlanReady = null;
            String placementAssessedLevel = null;
            LearningPlanView placementPlan = null;
            if (extras != null && extras.placement() != null) {
                PlacementLessonService.PlacementView p = extras.placement();
                placementStage = p.stage();
                placementStepLabel = p.stepLabel();
                placementStepIndex = p.stepIndex();
                placementStepTotal = p.stepTotal();
                placementPlanReady = p.planReady();
                placementAssessedLevel = p.assessedLevel();
                placementPlan = LearningPlanView.of(p.learningPlan());
            }
            return new SessionView(s.id(), s.mode().json(), s.topic(), s.cefr().name(), s.supportLevel(),
                    s.startedAt(), s.endedAt(), s.model(), SessionSummaryView.of(s.summary()),
                    lessonPhase, phaseLabel, minutesRemaining, targetMinutes,
                    placementStage, placementStepLabel, placementStepIndex, placementStepTotal,
                    placementPlanReady, placementAssessedLevel, placementPlan);
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
                              List<Integer> studyWeekdays, String targetEndDate, List<String> levels, String model,
                              String note) {
        static ProfileView of(UserProfile p, String model) {
            String note = switch (p.cefr()) {
                case C1, C2 -> "На уровнях C1–C2 стиль небольшой локальной модели упрощён: нюансы и идиомы она передаёт хуже, чем живой преподаватель.";
                default -> null;
            };
            String targetEnd = p.targetEndDate() == null ? null : p.targetEndDate().toString();
            return new ProfileView(p.id(), p.displayName(), p.nativeLang(), p.targetLang(), p.cefr().name(),
                    p.voiceEnabled(), p.dailyGoalMin(), DailyGoalMinutes.CHOICES, p.studyWeekdays(), targetEnd,
                    List.of("A1", "A2", "B1", "B2", "C1", "C2"), model, note);
        }
    }

    public record ScheduleWeekView(String weekStart, List<Integer> studyWeekdays, List<ScheduleSlotView> slots) {
        static ScheduleWeekView of(study.ai_foreign_language_teacher.schedule.ScheduleService.WeekView w) {
            return new ScheduleWeekView(w.weekStart().toString(), w.studyWeekdays(),
                    w.slots().stream().map(ScheduleSlotView::of).toList());
        }
    }

    public record ScheduleMonthView(String monthStart, List<Integer> studyWeekdays, List<ScheduleSlotView> slots) {
        static ScheduleMonthView of(study.ai_foreign_language_teacher.schedule.ScheduleService.MonthView month) {
            return new ScheduleMonthView(month.monthStart().toString(), month.studyWeekdays(),
                    month.slots().stream().map(ScheduleSlotView::of).toList());
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

    public record CompleteActivityRequest(String activityType) {
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

    public record SpeakingPhraseView(long runId, String phrase) {
        static SpeakingPhraseView of(study.ai_foreign_language_teacher.practice.SpeakingPracticeService.PhraseView v) {
            return new SpeakingPhraseView(v.runId(), v.phrase());
        }
    }

    public record SpeakingMismatchView(String expected, String heard, String kind) {
        static SpeakingMismatchView of(study.ai_foreign_language_teacher.practice.SpeechAligner.WordMismatch m) {
            return new SpeakingMismatchView(m.expected(), m.heard(), m.kind());
        }
    }

    public record SpeakingGradeView(String heard, List<SpeakingMismatchView> mismatches) {
        static SpeakingGradeView of(study.ai_foreign_language_teacher.practice.SpeakingPracticeService.GradeView v) {
            return new SpeakingGradeView(v.heard(),
                    v.mismatches().stream().map(SpeakingMismatchView::of).toList());
        }
    }

    public record ListeningCustomRequest(String text) {
    }

    public record ListeningQuestionView(String question, List<String> acceptable) {
        static ListeningQuestionView of(study.ai_foreign_language_teacher.practice.ListeningPracticeService.QuestionView q) {
            return new ListeningQuestionView(q.question(), q.acceptable());
        }
    }

    public record ListeningStartView(long runId, String passage, List<ListeningQuestionView> questions, String warning) {
        static ListeningStartView of(study.ai_foreign_language_teacher.practice.ListeningPracticeService.StartView v) {
            return new ListeningStartView(v.runId(), v.passage(),
                    v.questions().stream().map(ListeningQuestionView::of).toList(), v.warning());
        }
    }

    public record ListeningSubmitRequest(List<String> answers) {
    }

    public record ListeningSubmitView(int correct, int total, List<Boolean> results) {
        static ListeningSubmitView of(study.ai_foreign_language_teacher.practice.ListeningPracticeService.SubmitView v) {
            return new ListeningSubmitView(v.correct(), v.total(), v.results());
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
