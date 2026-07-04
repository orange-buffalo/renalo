ALTER TABLE users
    ADD COLUMN issue_refresh_token_on_passkey_login BOOLEAN NOT NULL DEFAULT TRUE;
