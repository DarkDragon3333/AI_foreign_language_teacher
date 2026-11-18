package study.ai_foreign_language_teacher.domain;

import java.time.LocalDate;

public record ScheduleSlot(
        long id,
        long userId,
        LocalDate slotDate,
        ActivityType activityType,
        SlotStatus status,
        Long sessionId,
        String planNote) {
}