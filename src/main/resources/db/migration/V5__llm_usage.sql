-- F10: cost guardrails - LLM usage per user and (UTC) day, booked after every chat model call
-- (Spring AI observation -> LlmUsageObservationHandler -> one atomic upsert). Budgets are checked against this
-- table before LLM endpoints run; SUM(cost_usd) of a day is the global spend cap.
CREATE TABLE llm_usage (
    user_id           UUID           NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    usage_day         DATE           NOT NULL,
    calls             INTEGER        NOT NULL DEFAULT 0 CHECK (calls >= 0),
    prompt_tokens     BIGINT         NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT         NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
    -- list price from rag.eval.models; calls with an unknown price only count tokens (unpriced_calls)
    cost_usd          NUMERIC(14, 6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    unpriced_calls    INTEGER        NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, usage_day)
);
CREATE INDEX llm_usage_day ON llm_usage (usage_day);
