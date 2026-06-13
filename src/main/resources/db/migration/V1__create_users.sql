CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('USER', 'ADMIN'))
);

CREATE INDEX users_type_idx ON users (type);
