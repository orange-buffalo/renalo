ALTER TABLE users
    ADD COLUMN password_sign_in_disabled BOOLEAN NOT NULL DEFAULT FALSE;
