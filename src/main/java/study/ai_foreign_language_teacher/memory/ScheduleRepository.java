package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.ScheduleSlot;
import study.ai_foreign_language_teacher.domain.SlotStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {

    Optional<ScheduleSlot> find(long userId, LocalDate date);

    List<ScheduleSlot> between(long userId, LocalDate from, LocalDate to);

    ScheduleSlot upsert(long userId, LocalDate date, ActivityType type, SlotStatus status, Long sessionId, String planNote);

    void updateStatus(long userId, LocalDate date, SlotStatus status);

    void updateType(long userId, LocalDate date, ActivityType type, String planNote);
}