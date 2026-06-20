CREATE TABLE expenses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tracking_account_id BIGINT NOT NULL REFERENCES tracking_accounts(id) ON DELETE RESTRICT,
    category_id BIGINT NOT NULL REFERENCES expense_categories(id) ON DELETE RESTRICT,
    date_time TIMESTAMPTZ NOT NULL,
    amount_minor BIGINT NOT NULL,
    notes TEXT,
    CONSTRAINT expenses_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT expenses_notes_not_blank CHECK (notes IS NULL OR length(trim(notes)) > 0)
);

CREATE INDEX expenses_user_id_date_time_idx ON expenses(user_id, date_time DESC);
CREATE INDEX expenses_tracking_account_id_idx ON expenses(tracking_account_id);
CREATE INDEX expenses_category_id_idx ON expenses(category_id);
