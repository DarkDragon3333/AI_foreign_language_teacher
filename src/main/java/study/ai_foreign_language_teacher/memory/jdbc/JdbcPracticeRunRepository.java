package study.ai_foreign_language_teacher.memory.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcPracticeRunRepository implements PracticeRunRepository {

    private static final RowMapper<PracticeRun> ROW = (rs, i) -> new PracticeRun(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("kind"),
            Cefr.parseOr(rs.getString("cefr_snapshot"), Cefr.B1),
            rs.getString("state"),
            instant(rs, "started_at"),
            instant(rs, "ended_at"));

    private final JdbcClient jdbc;

    public JdbcPracticeRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public PracticeRun create(long userId, String kind, String cefr, String stateJson) {
        return jdbc.sql("""
                        INSERT INTO practice_runs (user_id, kind, cefr_snapshot, state)
                        VALUES (:userId, :kind, :cefr, CAST(:state AS jsonb))
                        RETURNING *""")
                .param("userId", userId)
                .param("kind", kind)
                .param("cefr", cefr)
                .param("state", stateJson)
                .query(ROW).single();
    }

    @Override
    public Optional<PracticeRun> find(long id) {
        return jdbc.sql("SELECT id, user_id, kind, cefr_snapshot, state::text AS state, started_at, ended_at "
                        + "FROM practice_runs WHERE id = :id")
                .param("id", id)
                .query(ROW).optional();
    }

    @Override
    public PracticeRun save(PracticeRun run) {
        return jdbc.sql("""
                        UPDATE practice_runs SET state = CAST(:state AS jsonb), ended_at = :ended
                        WHERE id = :id
                        RETURNING id, user_id, kind, cefr_snapshot, state::text AS state, started_at, ended_at""")
                .param("state", run.stateJson())
                .param("ended", run.endedAt() == null ? null : Timestamp.from(run.endedAt()))
                .param("id", run.id())
                .query(ROW).single();
    }
}