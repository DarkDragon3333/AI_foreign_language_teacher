package study.ai_foreign_language_teacher.memory.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.ScheduleSlot;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.memory.ScheduleRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcScheduleRepository implements ScheduleRepository {

    private static final RowMapper<ScheduleSlot> SLOT = (rs, i) -> new ScheduleSlot(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getDate("slot_date").toLocalDate(),
            ActivityType.parse(rs.getString("activity_type")),
            SlotStatus.parse(rs.getString("status")),
            (Long) rs.getObject("session_id"),
            rs.getString("plan_note"));

    private final JdbcClient jdbc;

    public JdbcScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ScheduleSlot> find(long userId, LocalDate date) {
        return jdbc.sql("SELECT * FROM schedule_slots WHERE user_id = :userId AND slot_date = :date")
                .param("userId", userId)
                .param("date", date)
                .query(SLOT).optional();
    }

    @Override
    public List<ScheduleSlot> between(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT * FROM schedule_slots
                        WHERE user_id = :userId AND slot_date >= :from AND slot_date <= :to
                        ORDER BY slot_date""")
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query(SLOT).list();
    }

    @Override
    public ScheduleSlot upsert(long userId, LocalDate date, ActivityType type, SlotStatus status, Long sessionId,
                               String planNote) {
        return jdbc.sql("""
                        INSERT INTO schedule_slots (user_id, slot_date, activity_type, status, session_id, plan_note)
                        VALUES (:userId, :date, :type, :status, :sessionId, :note)
                        ON CONFLICT (user_id, slot_date) DO UPDATE SET
                            activity_type = EXCLUDED.activity_type,
                            status = EXCLUDED.status,
                            session_id = COALESCE(EXCLUDED.session_id, schedule_slots.session_id),
                            plan_note = COALESCE(EXCLUDED.plan_note, schedule_slots.plan_note)
                        RETURNING *""")
                .param("userId", userId)
                .param("date", date)
                .param("type", type.name())
                .param("status", status.name())
                .param("sessionId", sessionId, java.sql.Types.BIGINT)
                .param("note", planNote, java.sql.Types.VARCHAR)
                .query(SLOT).single();
    }

    @Override
    public void updateStatus(long userId, LocalDate date, SlotStatus status) {
        jdbc.sql("UPDATE schedule_slots SET status = :status WHERE user_id = :userId AND slot_date = :date")
                .param("status", status.name())
                .param("userId", userId)
                .param("date", date)
                .update();
    }

    @Override
    public void updateType(long userId, LocalDate date, ActivityType type, String planNote) {
        jdbc.sql("""
                        UPDATE schedule_slots SET activity_type = :type, plan_note = :note
                        WHERE user_id = :userId AND slot_date = :date""")
                .param("type", type.name())
                .param("note", planNote)
                .param("userId", userId)
                .param("date", date)
                .update();
    }
}