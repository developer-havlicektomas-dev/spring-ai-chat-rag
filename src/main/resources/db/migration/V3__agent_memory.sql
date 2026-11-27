-- F8: Koog ChatMemory (research agent, POST /kotlin/agent/research) persisted per conversation.
-- One row per conversation: Koog stores the whole (pre-processed, windowed) history at the end of every run,
-- so a JSONB document that is replaced each time fits better than one row per message.
CREATE TABLE agent_memory (
    conversation_id VARCHAR(64)  PRIMARY KEY,
    messages        JSONB        NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
