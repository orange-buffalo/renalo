CREATE TABLE expense_categories (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL
);

CREATE INDEX expense_categories_user_id_name_idx ON expense_categories(user_id, name);
