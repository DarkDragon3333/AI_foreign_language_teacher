package study.ai_foreign_language_teacher.memory.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.ScheduleSlot;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.memory.ScheduleRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("memory")
public class InMemoryScheduleRepository implements ScheduleRepository {

    private final AtomicLong seq = new AtomicLong();
    private final Map<String, ScheduleSlot> slots = new ConcurrentHashMap<>();

    private static String key(long userId, LocalDate date) {
        return userId + ":" + date;
    }

    @Override
    public Optional<ScheduleSlot> find(long userId, LocalDate date) {
        return Optional.ofNullable(slots.get(key(userId, date)));
    }

    @Override
    public List<ScheduleSlot> between(long userId, LocalDate from, LocalDate to) {
        List<ScheduleSlot> out = new ArrayList<>();
        for (ScheduleSlot s : slots.values()) {
            if (s.userId() == userId && !s.slotDate().isBefore(from) && !s.slotDate().isAfter(to)) {
                out.add(s);
            }
        }
        out.sort((a, b) -> a.slotDate().compareTo(b.slotDate()));
        return out;
    }

    @Override
    public ScheduleSlot upsert(long userId, LocalDate date, ActivityType type, SlotStatus status, Long sessionId,
                               String planNote) {
        String k = key(userId, date);
        ScheduleSlot existing = slots.get(k);
        long id = existing == null ? seq.incrementAndGet() : existing.id();
        Long sid = sessionId != null ? sessionId : existing == null ? null : existing.sessionId();
        String note = planNote != null ? planNote : existing == null ? null : existing.planNote();
        ScheduleSlot slot = new ScheduleSlot(id, userId, date, type, status, sid, note);
        slots.put(k, slot);
        return slot;
    }

    @Override
    public void updateStatus(long userId, LocalDate date, SlotStatus status) {
        find(userId, date).ifPresent(s -> slots.put(key(userId, date),
                new ScheduleSlot(s.id(), userId, date, s.activityType(), status, s.sessionId(), s.planNote())));
    }

    @Override
    public void updateType(long userId, LocalDate date, ActivityType type, String planNote) {
        find(userId, date).ifPresent(s -> slots.put(key(userId, date),
                new ScheduleSlot(s.id(), userId, date, type, s.status(), s.sessionId(), planNote)));
    }
}