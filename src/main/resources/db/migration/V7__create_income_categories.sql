CREATE TABLE income_categories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL
);

CREATE INDEX income_categories_user_id_name_idx ON income_categories(user_id, name);

INSERT INTO income_categories (user_id, name)
SELECT id, 'General'
FROM users
WHERE type = 'USER';
