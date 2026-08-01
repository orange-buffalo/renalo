CREATE TABLE ai_chat_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(100) NOT NULL,
    external_response_id TEXT,
    model_alias VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ai_chat_conversations_title_not_blank CHECK (length(trim(title)) > 0),
    CONSTRAINT ai_chat_conversations_model_alias_not_blank
        CHECK (model_alias IS NULL OR length(trim(model_alias)) > 0)
);

CREATE INDEX ai_chat_conversations_user_updated_idx
    ON ai_chat_conversations (user_id, updated_at DESC, id DESC);
