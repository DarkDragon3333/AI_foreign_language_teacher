package study.ai_foreign_language_teacher.schedule;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.inmemory.InMemoryScheduleRepository;
import study.ai_foreign_language_teacher.memory.rules.ReviewService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleServiceTest {

    @Test
    void normalizeWeekdaysDefaultsWhenEmpty() {
        assertEquals(List.of(1, 2, 3, 4, 5), ScheduleService.normalizeWeekdays(List.of()));
    }

    @Test
    void normalizeWeekdaysSortsAndDedupes() {
        assertEquals(List.of(1, 3, 5), ScheduleService.normalizeWeekdays(List.of(5, 1, 3, 1, 9)));
    }

    @Test
    void completeTodayMarksMatchingPlannedSlot() {
        InMemoryScheduleRepository slots = new InMemoryScheduleRepository();
        LocalDate today = LocalDate.now();
        slots.upsert(1, today, ActivityType.GRAMMAR, SlotStatus.PLANNED, null, null);
        ScheduleService service = service(slots, List.of(1, 2, 3, 4, 5));

        ScheduleService.SlotView view = service.completeToday(ActivityType.GRAMMAR);

        assertNotNull(view);
        assertEquals("done", view.status());
        assertEquals("grammar", view.activityType());
    }

    @Test
    void weekReturnsOnlySlotsOnStudyWeekdays() {
        InMemoryScheduleRepository slots = new InMemoryScheduleRepository();
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate saturday = monday.plusDays(5);
        slots.upsert(1, monday, ActivityType.GRAMMAR, SlotStatus.PLANNED, null, null);
        slots.upsert(1, saturday, ActivityType.GRAMMAR, SlotStatus.PLANNED, null, null);
        ScheduleService service = service(slots, List.of(1, 2, 3, 4, 5));

        ScheduleService.WeekView week = service.week();

        assertTrue(week.slots().stream().anyMatch(s -> s.date().equals(monday)));
        assertFalse(week.slots().stream().anyMatch(s -> s.date().equals(saturday)));
    }

    @Test
    void monthKeepsHistoryAndPlansStudyDaysAhead() {
        InMemoryScheduleRepository slots = new InMemoryScheduleRepository();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<Integer> study = List.of(1, 2, 3, 4, 5);
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate seedTo = weekEnd.isAfter(monthEnd) ? weekEnd : monthEnd;
        for (LocalDate date = weekStart; !date.isAfter(seedTo); date = date.plusDays(1)) {
            if (study.contains(date.getDayOfWeek().getValue())) {
                slots.upsert(1, date, ActivityType.GRAMMAR,
                        date.isBefore(today) ? SlotStatus.SKIPPED : SlotStatus.PLANNED, null, null);
            }
        }
        LocalDate doneOffDay = null;
        LocalDate stalePlan = null;
        for (LocalDate date = monthStart; date.isBefore(weekStart); date = date.plusDays(1)) {
            int dow = date.getDayOfWeek().getValue();
            if (doneOffDay == null && !study.contains(dow)) {
                doneOffDay = date;
            }
            if (stalePlan == null && study.contains(dow)) {
                stalePlan = date;
            }
        }
        if (doneOffDay != null) {
            slots.upsert(1, doneOffDay, ActivityType.CONVERSATION, SlotStatus.DONE, null, null);
        }
        if (stalePlan != null) {
            slots.upsert(1, stalePlan, ActivityType.REVIEW, SlotStatus.PLANNED, null, null);
        }
        LocalDate futureOff = null;
        for (LocalDate date = today; !date.isAfter(monthEnd); date = date.plusDays(1)) {
            if (!study.contains(date.getDayOfWeek().getValue())) {
                futureOff = date;
                slots.upsert(1, futureOff, ActivityType.SPEAKING, SlotStatus.PLANNED, null, null);
                break;
            }
        }

        ScheduleService.MonthView month = service(slots, study).month(today.getYear(), today.getMonthValue());
        List<LocalDate> dates = month.slots().stream().map(ScheduleService.SlotView::date).toList();

        if (doneOffDay != null) {
            assertTrue(dates.contains(doneOffDay));
        }
        if (stalePlan != null) {
            assertFalse(dates.contains(stalePlan));
        }
        if (futureOff != null) {
            assertFalse(dates.contains(futureOff));
        }
        for (LocalDate date = today; !date.isAfter(monthEnd); date = date.plusDays(1)) {
            if (study.contains(date.getDayOfWeek().getValue())) {
                assertTrue(dates.contains(date), date.toString());
            }
        }
        for (ScheduleService.SlotView slot : month.slots()) {
            if (slot.date().isBefore(today)) {
                assertTrue(slot.status().equals("done") || slot.status().equals("skipped"), slot.date().toString());
            }
        }
    }

    @Test
    void monthHidesPlannedSlotsAfterTargetEndDate() {
        InMemoryScheduleRepository slots = new InMemoryScheduleRepository();
        LocalDate today = LocalDate.now();
        LocalDate targetEnd = today.plusDays(3);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<Integer> study = List.of(1, 2, 3, 4, 5, 6, 7);
        for (LocalDate date = today; !date.isAfter(monthEnd); date = date.plusDays(1)) {
            slots.upsert(1, date, ActivityType.GRAMMAR, SlotStatus.PLANNED, null, null);
        }
        ProfileRepository profiles = new ProfileRepository() {
            private UserProfile profile = new UserProfile(
                    1, "Student", "ru", "en", Cefr.A2, false, 10, study, targetEnd);

            @Override
            public UserProfile getOrCreateDefault() {
                return profile;
            }

            @Override
            public UserProfile save(UserProfile updated) {
                profile = updated;
                return profile;
            }
        };
        ReviewService reviews = org.mockito.Mockito.mock(ReviewService.class);
        when(reviews.queue()).thenReturn(new ReviewService.Queue(0, List.of()));
        ErrorRepository errors = org.mockito.Mockito.mock(ErrorRepository.class);
        when(errors.top(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        ScheduleService schedule = new ScheduleService(profiles, slots, null, errors, reviews, null);

        ScheduleService.MonthView month = schedule.month(today.getYear(), today.getMonthValue());

        assertTrue(month.slots().stream().noneMatch(s -> s.date().isAfter(targetEnd) && "planned".equals(s.status())));
    }

    @Test
    void completeTodayLeavesDifferentActivity() {
        InMemoryScheduleRepository slots = new InMemoryScheduleRepository();
        LocalDate today = LocalDate.now();
        slots.upsert(1, today, ActivityType.CONVERSATION, SlotStatus.PLANNED, null, null);
        ScheduleService service = service(slots, List.of(1, 2, 3, 4, 5));

        ScheduleService.SlotView view = service.completeToday(ActivityType.GRAMMAR);

        assertNotNull(view);
        assertEquals("planned", view.status());
        assertEquals("conversation", view.activityType());
    }

    private static ScheduleService service(InMemoryScheduleRepository slots, List<Integer> studyWeekdays) {
        ProfileRepository profiles = new ProfileRepository() {
            private UserProfile profile = new UserProfile(
                    1, "Student", "ru", "en", Cefr.A2, false, 10, studyWeekdays, null);

            @Override
            public UserProfile getOrCreateDefault() {
                return profile;
            }

            @Override
            public UserProfile save(UserProfile updated) {
                profile = updated;
                return profile;
            }
        };
        ReviewService reviews = mock(ReviewService.class);
        when(reviews.queue()).thenReturn(new ReviewService.Queue(0, List.of()));
        ErrorRepository errors = mock(ErrorRepository.class);
        when(errors.top(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        return new ScheduleService(profiles, slots, null, errors, reviews, null);
    }
}