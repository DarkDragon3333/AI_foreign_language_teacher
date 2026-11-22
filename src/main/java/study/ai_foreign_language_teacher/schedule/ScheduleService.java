package study.ai_foreign_language_teacher.schedule;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.DailyGoalMinutes;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.ScheduleSlot;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.domain.LearningPlan;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.ScheduleRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.memory.rules.ReviewService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class ScheduleService {

    private final ProfileRepository profiles;
    private final ScheduleRepository schedule;
    private final SessionRepository sessions;
    private final ErrorRepository errors;
    private final ReviewService reviews;
    private final PlanAdvisor advisor;

    public ScheduleService(ProfileRepository profiles, ScheduleRepository schedule, SessionRepository sessions,
                           ErrorRepository errors, ReviewService reviews, PlanAdvisor advisor) {
        this.profiles = profiles;
        this.schedule = schedule;
        this.sessions = sessions;
        this.errors = errors;
        this.reviews = reviews;
        this.advisor = advisor;
    }

    public record WeekView(LocalDate weekStart, List<Integer> studyWeekdays, List<SlotView> slots) {
    }

    public record MonthView(LocalDate monthStart, List<Integer> studyWeekdays, List<SlotView> slots) {
    }

    public record SlotView(LocalDate date, String activityType, String status, Long sessionId, String planNote) {
        static SlotView of(ScheduleSlot s) {
            return new SlotView(s.slotDate(), s.activityType().json(), s.status().json(), s.sessionId(), s.planNote());
        }
    }

    public WeekView week() {
        UserProfile profile = profiles.getOrCreateDefault();
        refresh(profile);
        LocalDate start = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        List<Integer> studyDays = profile.studyWeekdays();
        List<SlotView> slots = schedule.between(profile.id(), start, end).stream()
                .filter(s -> studyDays.contains(s.slotDate().getDayOfWeek().getValue()))
                .filter(s -> !afterTarget(profile, s.slotDate()) || s.status() != SlotStatus.PLANNED)
                .map(SlotView::of)
                .toList();
        return new WeekView(start, studyDays, slots);
    }

    /**
     * Месяц для календаря. Done и Skipped остаются как были.
     * Учебные дни с сегодня до конца месяца, если слота ещё нет, становятся Planned.
     */
    public MonthView month(int year, int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be 1..12");
        }
        UserProfile profile = profiles.getOrCreateDefault();
        refresh(profile);
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        ensureAhead(profile, start, end);
        LocalDate today = LocalDate.now();
        List<Integer> studyDays = profile.studyWeekdays();
        List<SlotView> slots = schedule.between(profile.id(), start, end).stream()
                .filter(slot -> visibleInMonth(slot, studyDays, today, profile.targetEndDate()))
                .map(SlotView::of)
                .toList();
        return new MonthView(start, studyDays, slots);
    }

    /** Записывает слоты из placement-плана; прошедшие дни и Done/Skipped не трогаем. */
    public void applyLearningPlan(LearningPlan plan) {
        if (plan == null) {
            return;
        }
        UserProfile profile = profiles.getOrCreateDefault();
        List<Integer> days = plan.studyWeekdays() == null || plan.studyWeekdays().isEmpty()
                ? profile.studyWeekdays() : normalizeWeekdays(plan.studyWeekdays());
        int goal = DailyGoalMinutes.normalize(plan.dailyGoalMin());
        LocalDate targetEnd = resolveTargetEnd(plan, profile);
        profile = new UserProfile(profile.id(), profile.displayName(), profile.nativeLang(), profile.targetLang(),
                profile.cefr(), profile.voiceEnabled(), goal, days, targetEnd);
        profiles.save(profile);
        if (plan.monthSlots() == null || plan.monthSlots().isEmpty()) {
            refresh(profile);
            return;
        }
        LocalDate today = LocalDate.now();
        for (LearningPlan.MonthSlot slot : plan.monthSlots()) {
            if (slot.date() == null || slot.activityType() == null) {
                continue;
            }
            LocalDate date = LocalDate.parse(slot.date());
            if (date.isBefore(today) || afterTarget(profile, date)) {
                continue;
            }
            ActivityType type = ActivityType.parse(slot.activityType());
            var existing = schedule.find(profile.id(), date);
            if (existing.isPresent() && existing.get().status() != SlotStatus.PLANNED) {
                continue;
            }
            SlotStatus status = existing.map(ScheduleSlot::status).orElse(SlotStatus.PLANNED);
            schedule.upsert(profile.id(), date, type, status, null, "Placement plan");
        }
        refresh(profile);
    }

    public WeekView updateWeekdays(List<Integer> days) {
        UserProfile current = profiles.getOrCreateDefault();
        List<Integer> clean = normalizeWeekdays(days);
        UserProfile updated = new UserProfile(current.id(), current.displayName(), current.nativeLang(),
                current.targetLang(), current.cefr(), current.voiceEnabled(), current.dailyGoalMin(), clean,
                current.targetEndDate());
        profiles.save(updated);
        refresh(updated);
        return week();
    }

    public SlotView patchSlot(LocalDate date, ActivityType type, SlotStatus status) {
        UserProfile profile = profiles.getOrCreateDefault();
        if (type != null) {
            schedule.upsert(profile.id(), date, type,
                    schedule.find(profile.id(), date).map(ScheduleSlot::status).orElse(SlotStatus.PLANNED),
                    null, "Changed by learner");
        }
        if (status != null) {
            if (schedule.find(profile.id(), date).isEmpty() && status == SlotStatus.CANCELLED) {
                schedule.upsert(profile.id(), date, ActivityType.CONVERSATION, SlotStatus.CANCELLED, null, null);
            } else {
                schedule.updateStatus(profile.id(), date, status);
            }
        }
        return SlotView.of(schedule.find(profile.id(), date)
                .orElseThrow(() -> new IllegalArgumentException("No slot on " + date)));
    }

    /**
     * Ставит сегодняшний слот в Done, только если он ещё Planned и тип совпадает.
     * Другой тип или уже закрытый день не меняет.
     *
     * @return текущий слот или null, если на сегодня слота нет
     */
    public SlotView completeToday(ActivityType type) {
        if (type == null) {
            throw new IllegalArgumentException("activityType is required");
        }
        UserProfile profile = profiles.getOrCreateDefault();
        LocalDate today = LocalDate.now();
        var found = schedule.find(profile.id(), today);
        if (found.isEmpty()) {
            return null;
        }
        ScheduleSlot slot = found.get();
        if (slot.status() == SlotStatus.PLANNED && slot.activityType() == type) {
            schedule.updateStatus(profile.id(), today, SlotStatus.DONE);
            slot = schedule.find(profile.id(), today).orElse(slot);
        }
        return SlotView.of(slot);
    }

    public void onSessionEnded(LessonSession session) {
        LocalDate day = LocalDate.ofInstant(session.startedAt(), ZoneId.systemDefault());
        UserProfile profile = profiles.getOrCreateDefault();
        schedule.upsert(profile.id(), day, activityForSession(session), SlotStatus.DONE, session.id(),
                "Lesson finished");
    }

    private ActivityType activityForSession(LessonSession session) {
        return switch (session.mode()) {
            case DRILL -> ActivityType.GRAMMAR;
            case PLACEMENT -> ActivityType.CONVERSATION;
            default -> ActivityType.CONVERSATION;
        };
    }

    public PlanAdvisor.PlanRevision revisePlan() {
        UserProfile profile = profiles.getOrCreateDefault();
        InsightsService.InsightsBundle stats = InsightsService.collect(sessions, errors, profile.id(), 5);
        int reviewDue = reviews.queue().due();
        PlanAdvisor.PlanRevision revision = advisor.suggest(profile, stats, reviewDue, futureSlots(profile));
        applyRevision(profile, revision);
        return revision;
    }

    private List<ScheduleSlot> futureSlots(UserProfile profile) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(14);
        return schedule.between(profile.id(), today, end).stream()
                .filter(s -> s.status() == SlotStatus.PLANNED)
                .toList();
    }

    void applyRevision(UserProfile profile, PlanAdvisor.PlanRevision revision) {
        for (PlanAdvisor.SlotRevision r : revision.revisions()) {
            LocalDate date = LocalDate.parse(r.date());
            if (!date.isBefore(LocalDate.now())
                    && schedule.find(profile.id(), date).map(s -> s.status() == SlotStatus.PLANNED).orElse(false)) {
                schedule.updateType(profile.id(), date, ActivityType.parse(r.activityType()), r.reason());
            }
        }
    }

    private void ensureAhead(UserProfile profile, LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        LocalDate from = start.isBefore(today) ? today : start;
        if (from.isAfter(end)) {
            return;
        }
        List<Integer> days = profile.studyWeekdays();
        for (LocalDate date = from; !date.isAfter(end); date = date.plusDays(1)) {
            if (afterTarget(profile, date) || !days.contains(date.getDayOfWeek().getValue())) {
                continue;
            }
            if (schedule.find(profile.id(), date).isEmpty()) {
                schedule.upsert(profile.id(), date, pickType(profile, date), SlotStatus.PLANNED, null, "Auto");
            }
        }
    }

    private static boolean visibleInMonth(ScheduleSlot slot, List<Integer> studyDays, LocalDate today,
                                        LocalDate targetEnd) {
        SlotStatus status = slot.status();
        if (status == SlotStatus.DONE || status == SlotStatus.SKIPPED) {
            return true;
        }
        if (!studyDays.contains(slot.slotDate().getDayOfWeek().getValue())) {
            return false;
        }
        if (afterTarget(targetEnd, slot.slotDate())) {
            return false;
        }
        return !slot.slotDate().isBefore(today);
    }

    private static boolean afterTarget(UserProfile profile, LocalDate date) {
        return afterTarget(profile.targetEndDate(), date);
    }

    private static boolean afterTarget(LocalDate targetEnd, LocalDate date) {
        return targetEnd != null && date.isAfter(targetEnd);
    }

    private static LocalDate resolveTargetEnd(LearningPlan plan, UserProfile profile) {
        if (plan.targetEndDate() != null && !plan.targetEndDate().isBlank()) {
            try {
                return LocalDate.parse(plan.targetEndDate().strip());
            } catch (java.time.format.DateTimeParseException ex) {
                return profile.targetEndDate();
            }
        }
        return profile.targetEndDate();
    }

    public void refresh(UserProfile profile) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.with(DayOfWeek.MONDAY);
        LocalDate end = start.plusDays(6);
        List<Integer> days = profile.studyWeekdays();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            int dow = d.getDayOfWeek().getValue();
            if (!days.contains(dow)) {
                continue;
            }
            var existing = schedule.find(profile.id(), d);
            if (existing.isPresent()) {
                ScheduleSlot slot = existing.get();
                if (slot.status() == SlotStatus.PLANNED && d.isBefore(today)) {
                    schedule.updateStatus(profile.id(), d, SlotStatus.SKIPPED);
                }
                continue;
            }
            if (afterTarget(profile, d)) {
                continue;
            }
            if (d.isBefore(today)) {
                schedule.upsert(profile.id(), d, pickType(profile, d), SlotStatus.SKIPPED, null, "Past day");
            } else {
                schedule.upsert(profile.id(), d, pickType(profile, d), SlotStatus.PLANNED, null, "Auto");
            }
        }
    }

    ActivityType pickType(UserProfile profile, LocalDate date) {
        int reviewDue = reviews.queue().due();
        if (reviewDue > 0) {
            return ActivityType.REVIEW;
        }
        ErrorEvent top = errors.top(profile.id(), 1).stream().findFirst().orElse(null);
        if (top != null && top.count() >= 3) {
            return ActivityType.GRAMMAR;
        }
        int mod = date.getDayOfWeek().getValue() % 4;
        return switch (mod) {
            case 0 -> ActivityType.LISTENING;
            case 2 -> ActivityType.SPEAKING;
            default -> ActivityType.CONVERSATION;
        };
    }

    public static List<Integer> normalizeWeekdays(List<Integer> days) {
        if (days == null || days.isEmpty()) {
            return UserProfile.DEFAULT_STUDY_WEEKDAYS;
        }
        List<Integer> out = new ArrayList<>();
        for (Integer d : days) {
            if (d != null && d >= 1 && d <= 7 && !out.contains(d)) {
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            return UserProfile.DEFAULT_STUDY_WEEKDAYS;
        }
        out.sort(Integer::compareTo);
        return List.copyOf(out);
    }
}