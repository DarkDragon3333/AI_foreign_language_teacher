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

-- урок может идти на модели, заданной при старте; пусто — модель по умолчанию
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS model TEXT;

-- ---- Шаг 4: карточки правил, поиск по смыслу, FSRS ----

CREATE EXTENSION IF NOT EXISTS vector;

-- учебник: короткие проверенные заметки. Эмбеддинг считает CPU-модель paraphrase-multilingual-MiniLM-L12-v2 (384 числа).
CREATE TABLE IF NOT EXISTS rule_cards (
    slug         TEXT PRIMARY KEY,
    cefr_min     TEXT             NOT NULL,
    cefr_max     TEXT             NOT NULL,
    title        TEXT             NOT NULL,
    title_ru     TEXT             NOT NULL,
    rule_text    TEXT             NOT NULL,
    rule_ru      TEXT             NOT NULL,
    example_ok   TEXT             NOT NULL,
    example_bad  TEXT             NOT NULL,
    l1_trap      TEXT             NOT NULL,
    lt_rules     JSONB            NOT NULL DEFAULT '[]',
    keywords     JSONB            NOT NULL DEFAULT '[]',
    content_hash TEXT             NOT NULL,
    embedding    vector(384)
);

-- одна строка на ученика и карточку: когда показать правило снова
CREATE TABLE IF NOT EXISTS fsrs_cards (
    user_id     BIGINT           NOT NULL REFERENCES users (id),
    slug        TEXT             NOT NULL,
    state       TEXT             NOT NULL,
    due         TIMESTAMPTZ      NOT NULL,
    stability   DOUBLE PRECISION NOT NULL DEFAULT 0,
    difficulty  DOUBLE PRECISION NOT NULL DEFAULT 0,
    reps        INT              NOT NULL DEFAULT 0,
    lapses      INT              NOT NULL DEFAULT 0,
    last_review TIMESTAMPTZ,
    PRIMARY KEY (user_id, slug)
);
CREATE INDEX IF NOT EXISTS idx_fsrs_due ON fsrs_cards (user_id, due);

-- ---- Шаг 9: дневная цель и сводка урока ----

ALTER TABLE users ADD COLUMN IF NOT EXISTS daily_goal_min INT NOT NULL DEFAULT 10;

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS summary JSONB;

ALTER TABLE users ADD COLUMN IF NOT EXISTS study_weekdays JSONB NOT NULL DEFAULT '[1,2,3,4,5]'::jsonb;

CREATE TABLE IF NOT EXISTS schedule_slots (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id),
    slot_date     DATE        NOT NULL,
    activity_type TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    session_id    BIGINT      REFERENCES sessions (id),
    plan_note     TEXT,
    UNIQUE (user_id, slot_date)
);
CREATE INDEX IF NOT EXISTS idx_schedule_slots_user ON schedule_slots (user_id, slot_date);
