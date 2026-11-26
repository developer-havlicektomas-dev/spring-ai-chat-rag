-- F9: AI Study Guide spike - paid plans + daily quota.
-- app_user.plan already exists since V2 (default 'free'); now it is restricted to the plans the code knows.
UPDATE app_user SET plan = lower(plan);
ALTER TABLE app_user ADD CONSTRAINT app_user_plan_check CHECK (plan IN ('free', 'pro'));

-- One row per user and (UTC) day. The quota check is a single atomic upsert:
--   INSERT ... ON CONFLICT DO UPDATE SET generations = generations + 1 WHERE generations < :limit RETURNING generations
-- -> no row returned = quota exhausted, and two parallel requests can never both take the last slot.
CREATE TABLE study_usage (
    user_id     UUID    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    usage_day   DATE    NOT NULL,
    generations INTEGER NOT NULL DEFAULT 0 CHECK (generations >= 0),
    PRIMARY KEY (user_id, usage_day)
);

-- Append-only log of generations: cost tracking (tokens per plan), latency, how often we fall back.
CREATE TABLE study_generation (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    plan          VARCHAR(32)  NOT NULL,
    features      VARCHAR(128) NOT NULL,
    note_chars    INTEGER      NOT NULL,
    chunks        INTEGER      NOT NULL,
    quiz_size     INTEGER      NOT NULL,
    answer_type   VARCHAR(16)  NOT NULL,
    model         VARCHAR(64),
    input_tokens  INTEGER,
    output_tokens INTEGER,
    latency_ms    BIGINT       NOT NULL
);
CREATE INDEX study_generation_user_created ON study_generation (user_id, created_at DESC);
