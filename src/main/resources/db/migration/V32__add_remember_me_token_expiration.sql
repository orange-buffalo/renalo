ALTER TABLE remember_me_tokens ADD COLUMN expires_at TIMESTAMPTZ;

-- Existing tokens were only bounded by a fixed 30-day cookie lifetime counted from login;
-- keep that same deadline, now enforced by the server.
UPDATE remember_me_tokens SET expires_at = created_at + INTERVAL '30 days';

ALTER TABLE remember_me_tokens ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_remember_me_tokens_expires_at ON remember_me_tokens (expires_at);
