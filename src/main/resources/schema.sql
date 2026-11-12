-- Схема шага 2. Таблицы следующих шагов (ошибки, карточки, FSRS) добавятся позже.
CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    display_name  TEXT        NOT NULL,
    native_lang   TEXT        NOT NULL DEFAULT 'ru',
    target_lang   TEXT        NOT NULL DEFAULT 'en',
    cefr          TEXT        NOT NULL DEFAULT 'B1',
    voice_enabled BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS sessions (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id),
    mode          TEXT        NOT NULL,
    topic         TEXT,
    cefr_snapshot TEXT        NOT NULL,
    support_level INT         NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS messages (
    id         BIGSERIAL PRIMARY KEY,
    session_id BIGINT      NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    role       TEXT        NOT NULL,
    text       TEXT        NOT NULL,
    payload    JSONB,
    audio_ref  TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_messages_session ON messages (session_id, id);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions (user_id, id DESC);

-- ---- Шаг 3: LanguageTool и журнал ошибок ----

-- сырой вывод LanguageTool по реплике ученика
CREATE TABLE IF NOT EXISTS lt_matches (
    id           BIGSERIAL PRIMARY KEY,
    message_id   BIGINT NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    rule_id      TEXT   NOT NULL,
    category     TEXT,
    message      TEXT,
    replacements TEXT,
    start_offset INT    NOT NULL,
    length       INT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lt_matches_message ON lt_matches (message_id);

-- нормализованные ошибки: одно правило — одна строка со счётчиком
CREATE TABLE IF NOT EXISTS error_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    rule_id     TEXT        NOT NULL,
    source      TEXT        NOT NULL,          -- lt | llm
    category    TEXT,
    title       TEXT,
    source_span TEXT,
    count       INT         NOT NULL DEFAULT 1,
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    severity    TEXT        NOT NULL DEFAULT 'normal',
    UNIQUE (user_id, rule_id)
);
