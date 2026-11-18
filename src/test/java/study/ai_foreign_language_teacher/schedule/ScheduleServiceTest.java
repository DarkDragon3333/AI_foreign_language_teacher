package study.ai_foreign_language_teacher.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleServiceTest {

    @Test
    void normalizeWeekdaysDefaultsWhenEmpty() {
        assertEquals(List.of(1, 2, 3, 4, 5), ScheduleService.normalizeWeekdays(List.of()));
    }

    @Test
    void normalizeWeekdaysSortsAndDedupes() {
        assertEquals(List.of(1, 3, 5), ScheduleService.normalizeWeekdays(List.of(5, 1, 3, 1, 9)));
    }
}