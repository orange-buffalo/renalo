CREATE TABLE account_adjustments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tracking_account_id BIGINT NOT NULL REFERENCES tracking_accounts(id) ON DELETE CASCADE,
    adjustment_amount_minor BIGINT NOT NULL
);
