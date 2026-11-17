package study.ai_foreign_language_teacher.memory.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.memory.ReviewRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!memory")
public class JdbcReviewRepository implements ReviewRepository {

    private final JdbcClient jdbc;

    public JdbcReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FsrsState> find(long userId, String slug) {
        return jdbc.sql("SELECT * FROM fsrs_cards WHERE user_id = :userId AND slug = :slug")
                .param("userId", userId)
                .param("slug", slug)
                .query(JdbcReviewRepository::map).optional();
    }

    @Override
    public void save(FsrsState state) {
        jdbc.sql("""
                        INSERT INTO fsrs_cards (user_id, slug, state, due, stability, difficulty, reps, lapses, last_review)
                        VALUES (:userId, :slug, :state, :due, :stability, :difficulty, :reps, :lapses, :lastReview)
                        ON CONFLICT (user_id, slug) DO UPDATE SET
                            state = EXCLUDED.state,
                            due = EXCLUDED.due,
                            stability = EXCLUDED.stability,
                            difficulty = EXCLUDED.difficulty,
                            reps = EXCLUDED.reps,
                            lapses = EXCLUDED.lapses,
                            last_review = EXCLUDED.last_review""")
                .param("userId", state.userId())
                .param("slug", state.slug())
                .param("state", state.state())
                .param("due", Timestamp.from(state.due()))
                .param("stability", state.stability())
                .param("difficulty", state.difficulty())
                .param("reps", state.reps())
                .param("lapses", state.lapses())
                .param("lastReview", state.lastReview() == null ? null : Timestamp.from(state.lastReview()))
                .update();
    }

    @Override
    public List<FsrsState> dueBy(long userId, Instant until) {
        return jdbc.sql("SELECT * FROM fsrs_cards WHERE user_id = :userId AND due <= :until ORDER BY due")
                .param("userId", userId)
                .param("until", Timestamp.from(until))
                .query(JdbcReviewRepository::map).list();
    }

    @Override
    public List<FsrsState> allForUser(long userId) {
        return jdbc.sql("SELECT * FROM fsrs_cards WHERE user_id = :userId ORDER BY slug")
                .param("userId", userId)
                .query(JdbcReviewRepository::map).list();
    }

    private static FsrsState map(ResultSet rs, int row) throws SQLException {
        Timestamp last = rs.getTimestamp("last_review");
        return new FsrsState(
                rs.getLong("user_id"),
                rs.getString("slug"),
                rs.getString("state"),
                rs.getTimestamp("due").toInstant(),
                rs.getDouble("stability"),
                rs.getDouble("difficulty"),
                rs.getInt("reps"),
                rs.getInt("lapses"),
                last == null ? null : last.toInstant());
    }
}
