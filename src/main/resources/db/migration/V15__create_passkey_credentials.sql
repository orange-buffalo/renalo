CREATE TABLE passkey_credentials (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id TEXT NOT NULL UNIQUE,
    user_handle TEXT NOT NULL,
    public_key_cose TEXT NOT NULL,
    signature_count BIGINT NOT NULL,
    device TEXT NOT NULL,
    transports TEXT,
    backup_eligible BOOLEAN,
    backed_up BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_passkey_credentials_user_id ON passkey_credentials (user_id);
CREATE INDEX idx_passkey_credentials_user_handle ON passkey_credentials (user_handle);
