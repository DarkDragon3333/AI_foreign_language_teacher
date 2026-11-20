package study.ai_foreign_language_teacher.schedule;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.inmemory.InMemoryScheduleRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        assertEquals(1, week.slots().size());
        assertEquals(monday, week.slots().getFirst().date());
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
                    1, "Student", "ru", "en", Cefr.A2, false, 10, studyWeekdays);

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
        return new ScheduleService(profiles, slots, null, null, null, null);
    }
}