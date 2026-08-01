CREATE TABLE ai_chat_conversation_events (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES ai_chat_conversations(id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    item_type VARCHAR(50) NOT NULL,
    item JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ai_chat_conversation_events_sequence UNIQUE (conversation_id, sequence)
);

CREATE INDEX idx_ai_chat_conversation_events_conversation
    ON ai_chat_conversation_events (conversation_id, sequence);

ALTER TABLE ai_chat_conversations DROP COLUMN external_response_id;
