CREATE TABLE tracking_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    currency CHAR(3) NOT NULL,
    initial_balance_minor BIGINT NOT NULL DEFAULT 0,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT tracking_accounts_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT tracking_accounts_currency_uppercase CHECK (currency = upper(currency))
);

CREATE UNIQUE INDEX tracking_accounts_single_default_per_user
    ON tracking_accounts (user_id)
    WHERE is_default;

INSERT INTO tracking_accounts (user_id, name, currency, initial_balance_minor, is_default)
SELECT id, 'Main', 'AUD', 0, TRUE
FROM users
WHERE type = 'USER';
