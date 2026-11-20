package study.ai_foreign_language_teacher.memory.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.domain.StructuredPlanState;
import study.ai_foreign_language_teacher.domain.UserProfile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.MessageRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.memory.StorageHealth;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Хранилище в Postgres (основной режим). Схема — в resources/schema.sql. */
@Repository
@Profile("!memory")
public class JdbcStore implements ProfileRepository, SessionRepository, MessageRepository, ErrorRepository, StorageHealth {

    private final RowMapper<UserProfile> profileMapper = (rs, i) -> new UserProfile(
            rs.getLong("id"),
            rs.getString("display_name"),
            rs.getString("native_lang"),
            rs.getString("target_lang"),
            Cefr.parseOr(rs.getString("cefr"), Cefr.B1),
            rs.getBoolean("voice_enabled"),
            rs.getInt("daily_goal_min"),
            readWeekdays(rs));

    private final RowMapper<LessonSession> sessionMapper = (rs, i) -> new LessonSession(
            rs.getLong("id"),
            rs.getLong("user_id"),
            LessonMode.parse(rs.getString("mode")),
            rs.getString("topic"),
            Cefr.parseOr(rs.getString("cefr_snapshot"), Cefr.B1),
            rs.getInt("support_level"),
            instant(rs, "started_at"),
            instant(rs, "ended_at"),
            rs.getString("model"),
            readSummary(rs),
            readPlanState(rs));

    private static final RowMapper<ChatMessage> MESSAGE = (rs, i) -> new ChatMessage(
            rs.getLong("id"),
            rs.getLong("session_id"),
            Role.valueOf(rs.getString("role")),
            rs.getString("text"),
            rs.getString("payload"),
            instant(rs, "created_at"));

    private static final RowMapper<ErrorEvent> ERROR = (rs, i) -> new ErrorEvent(
            rs.getString("rule_id"),
            rs.getString("source"),
            rs.getString("category"),
            rs.getString("title"),
            rs.getString("source_span"),
            rs.getInt("count"),
            instant(rs, "last_seen"));

    private static final String MESSAGE_COLUMNS = "id, session_id, role, text, payload::text AS payload, created_at";

    private final JdbcClient jdbc;
    private final TutorProperties props;
    private final ObjectMapper mapper;

    public JdbcStore(JdbcClient jdbc, TutorProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.props = props;
        this.mapper = mapper;
    }

    private List<Integer> readWeekdays(ResultSet rs) throws SQLException {
        String json = rs.getString("study_weekdays");
        if (json == null || json.isBlank()) {
            return UserProfile.DEFAULT_STUDY_WEEKDAYS;
        }
        try {
            List<Integer> days = mapper.readValue(json, new TypeReference<List<Integer>>() {
            });
            return days == null || days.isEmpty() ? UserProfile.DEFAULT_STUDY_WEEKDAYS : days;
        } catch (JacksonException e) {
            return UserProfile.DEFAULT_STUDY_WEEKDAYS;
        }
    }

    private SessionSummary readSummary(ResultSet rs) throws SQLException {
        String json = rs.getString("summary");
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, SessionSummary.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private StructuredPlanState readPlanState(ResultSet rs) throws SQLException {
        String json = rs.getString("plan_state");
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, StructuredPlanState.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    // ---- профиль ----

    @Override
    public synchronized UserProfile getOrCreateDefault() {
        Optional<UserProfile> existing = jdbc.sql("SELECT * FROM users ORDER BY id LIMIT 1").query(profileMapper).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        TutorProperties.Lesson l = props.lesson();
        return jdbc.sql("""
                        INSERT INTO users (display_name, native_lang, target_lang, cefr, voice_enabled, daily_goal_min, study_weekdays)
                        VALUES (:name, :nativeLang, :targetLang, :cefr, FALSE, :goal, CAST(:weekdays AS jsonb))
                        RETURNING *""")
                .param("name", "Student")
                .param("nativeLang", l.nativeLang())
                .param("targetLang", l.targetLang())
                .param("cefr", Cefr.parseOr(l.defaultCefr(), Cefr.B1).name())
                .param("goal", UserProfile.DEFAULT_DAILY_GOAL_MIN)
                .param("weekdays", mapper.writeValueAsString(UserProfile.DEFAULT_STUDY_WEEKDAYS))
                .query(profileMapper).single();
    }

    @Override
    public UserProfile save(UserProfile p) {
        return jdbc.sql("""
                        UPDATE users SET display_name = :name, native_lang = :nativeLang, target_lang = :targetLang,
                                         cefr = :cefr, voice_enabled = :voice, daily_goal_min = :goal,
                                         study_weekdays = CAST(:weekdays AS jsonb)
                        WHERE id = :id
                        RETURNING *""")
                .param("name", p.displayName())
                .param("nativeLang", p.nativeLang())
                .param("targetLang", p.targetLang())
                .param("cefr", p.cefr().name())
                .param("voice", p.voiceEnabled())
                .param("goal", p.dailyGoalMin())
                .param("weekdays", mapper.writeValueAsString(p.studyWeekdays()))
                .param("id", p.id())
                .query(profileMapper).single();
    }

    // ---- сессии ----

    @Override
    public LessonSession create(long userId, LessonMode mode, String topic, Cefr cefr, String model,
                                StructuredPlanState planState) {
        String planJson = planState == null ? null : mapper.writeValueAsString(planState);
        return jdbc.sql("""
                        INSERT INTO sessions (user_id, mode, topic, cefr_snapshot, model, plan_state)
                        VALUES (:userId, :mode, :topic, :cefr, :model, CAST(:planState AS jsonb))
                        RETURNING *""")
                .param("model", model, java.sql.Types.VARCHAR)
                .param("planState", planJson, java.sql.Types.VARCHAR)
                .param("userId", userId)
                .param("mode", mode.name())
                .param("topic", topic)
                .param("cefr", cefr.name())
                .query(sessionMapper).single();
    }

    @Override
    public void updatePlanState(long id, StructuredPlanState planState) {
        String json = planState == null ? null : mapper.writeValueAsString(planState);
        jdbc.sql("UPDATE sessions SET plan_state = CAST(:planState AS jsonb) WHERE id = :id")
                .param("planState", json, java.sql.Types.VARCHAR)
                .param("id", id)
                .update();
    }

    @Override
    public Optional<LessonSession> find(long id) {
        return jdbc.sql("SELECT * FROM sessions WHERE id = :id").param("id", id).query(sessionMapper).optional();
    }

    @Override
    public List<LessonSession> recent(long userId, int limit) {
        return jdbc.sql("SELECT * FROM sessions WHERE user_id = :userId ORDER BY id DESC LIMIT :limit")
                .param("userId", userId)
                .param("limit", limit)
                .query(sessionMapper).list();
    }

    @Override
    public Optional<LessonSession> findOpen(long userId) {
        return jdbc.sql("""
                        SELECT * FROM sessions WHERE user_id = :userId AND ended_at IS NULL
                        ORDER BY id DESC LIMIT 1""")
                .param("userId", userId)
                .query(sessionMapper).optional();
    }

    @Override
    public int practicedMinutesOnDay(long userId, LocalDate day) {
        Integer sum = jdbc.sql("""
                        SELECT COALESCE(SUM((summary->>'durationMinutes')::int), 0)
                        FROM sessions
                        WHERE user_id = :userId AND ended_at IS NOT NULL
                          AND started_at::date = :day AND summary IS NOT NULL""")
                .param("userId", userId)
                .param("day", day)
                .query(Integer.class).single();
        return sum == null ? 0 : sum;
    }

    @Override
    public void updateSupportLevel(long id, int supportLevel) {
        jdbc.sql("UPDATE sessions SET support_level = :level WHERE id = :id")
                .param("level", supportLevel).param("id", id).update();
    }

    @Override
    public void updateCefr(long id, Cefr cefr) {
        jdbc.sql("UPDATE sessions SET cefr_snapshot = :cefr WHERE id = :id")
                .param("cefr", cefr.name()).param("id", id).update();
    }

    @Override
    public void end(long id, SessionSummary summary) {
        String json = mapper.writeValueAsString(summary);
        jdbc.sql("""
                        UPDATE sessions SET ended_at = now(), summary = CAST(:summary AS jsonb)
                        WHERE id = :id AND ended_at IS NULL""")
                .param("summary", json)
                .param("id", id)
                .update();
    }

    // ---- сообщения ----

    @Override
    public ChatMessage add(long sessionId, Role role, String text, String payloadJson) {
        return jdbc.sql("INSERT INTO messages (session_id, role, text, payload) "
                        + "VALUES (:sessionId, :role, :text, CAST(:payload AS jsonb)) RETURNING " + MESSAGE_COLUMNS)
                .param("sessionId", sessionId)
                .param("role", role.name())
                .param("text", text)
                .param("payload", payloadJson, java.sql.Types.VARCHAR)
                .query(MESSAGE).single();
    }

    @Override
    public List<ChatMessage> lastN(long sessionId, int n) {
        List<ChatMessage> newestFirst = new ArrayList<>(
                jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE session_id = :sessionId ORDER BY id DESC LIMIT :n")
                        .param("sessionId", sessionId)
                        .param("n", n)
                        .query(MESSAGE).list());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Override
    public List<ChatMessage> all(long sessionId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM messages WHERE session_id = :sessionId ORDER BY id")
                .param("sessionId", sessionId)
                .query(MESSAGE).list();
    }

    @Override
    public int countUserTurns(long sessionId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId AND role = 'USER'")
                .param("sessionId", sessionId)
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public int countUserTurnsSince(long sessionId, Instant since) {
        if (since == null) {
            return countUserTurns(sessionId);
        }
        Integer count = jdbc.sql("""
                        SELECT COUNT(*) FROM messages
                        WHERE session_id = :sessionId AND role = 'USER' AND created_at >= :since""")
                .param("sessionId", sessionId)
                .param("since", Timestamp.from(since))
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    // ---- ошибки ----

    @Override
    public void saveMatches(long messageId, List<LtMatch> matches) {
        for (LtMatch m : matches) {
            jdbc.sql("""
                            INSERT INTO lt_matches (message_id, rule_id, category, message, replacements, start_offset, length)
                            VALUES (:messageId, :ruleId, :category, :message, :replacements, :startOffset, :length)""")
                    .param("messageId", messageId)
                    .param("ruleId", m.ruleId())
                    .param("category", m.category())
                    .param("message", m.message())
                    .param("replacements", String.join(" | ", m.replacements()))
                    .param("startOffset", m.offset())
                    .param("length", m.length())
                    .update();
        }
    }

    @Override
    public void record(long userId, String ruleId, String source, String category, String title, String span) {
        jdbc.sql("""
                        INSERT INTO error_events (user_id, rule_id, source, category, title, source_span)
                        VALUES (:userId, :ruleId, :source, :category, :title, :span)
                        ON CONFLICT (user_id, rule_id) DO UPDATE
                        SET count = error_events.count + 1, last_seen = now(),
                            source_span = EXCLUDED.source_span, title = EXCLUDED.title, category = EXCLUDED.category""")
                .param("userId", userId)
                .param("ruleId", ruleId)
                .param("source", source)
                .param("category", category)
                .param("title", title)
                .param("span", span)
                .update();
    }

    @Override
    public List<ErrorEvent> top(long userId, int limit) {
        return jdbc.sql("SELECT * FROM error_events WHERE user_id = :userId ORDER BY count DESC, last_seen DESC LIMIT :limit")
                .param("userId", userId)
                .param("limit", limit)
                .query(ERROR).list();
    }

    @Override
    public List<SessionSummary.RuleHit> topRulesInSession(long sessionId, int limit) {
        return jdbc.sql("""
                        SELECT lt.rule_id,
                               COALESCE(MAX(NULLIF(lt.message, '')), lt.rule_id) AS title,
                               COUNT(*)::int AS cnt
                        FROM lt_matches lt
                        JOIN messages m ON m.id = lt.message_id
                        WHERE m.session_id = :sessionId
                        GROUP BY lt.rule_id
                        ORDER BY cnt DESC
                        LIMIT :limit""")
                .param("sessionId", sessionId)
                .param("limit", limit)
                .query((rs, row) -> new SessionSummary.RuleHit(
                        rs.getString("rule_id"),
                        rs.getString("title"),
                        rs.getInt("cnt")))
                .list();
    }

    // ---- health ----

    @Override
    public boolean ready() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String kind() {
        return "postgres";
    }
}
