-- F8: minimal user concept, prep for the Study Guide SaaS backend.
-- external_id = what the client sends in X-User-Id today (later: the auth provider's subject, e.g. a JWT "sub").
-- Sessions are owned through Spring AI's indexed session metadata: AI_SESSION_INDEX(attr_name = 'principalName',
-- attr_value = app_user.external_id), so SessionService.findByMetadata("principalName", user) is one indexed query.
CREATE TABLE app_user (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id  VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(128),
    plan         VARCHAR(32)  NOT NULL DEFAULT 'free',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read model for admin/debugging: one row per chat session with its owner and message count.
CREATE VIEW chat_session_overview AS
SELECT s.id                                                            AS conversation_id,
       i.attr_value                                                    AS user_external_id,
       u.id                                                            AS user_id,
       s.created_at,
       s.expires_at,
       (SELECT count(*) FROM ai_session_event e
         WHERE e.session_id = s.id AND e.event_type = 'MESSAGE')       AS messages
FROM ai_session s
LEFT JOIN ai_session_index i ON i.session_id = s.id AND i.attr_name = 'principalName'
LEFT JOIN app_user u ON u.external_id = i.attr_value;
