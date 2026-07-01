CREATE TABLE passkey_challenges (
    id BIGSERIAL PRIMARY KEY,
    request_id TEXT NOT NULL UNIQUE,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('REGISTRATION', 'AUTHENTICATION')),
    request_json TEXT NOT NULL,
    device TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_passkey_challenges_expires_at ON passkey_challenges (expires_at);
