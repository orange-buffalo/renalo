ALTER TABLE transactions
    ADD COLUMN default_currency_amount_minor BIGINT,
    ADD COLUMN default_currency CHAR(3),
    ADD COLUMN default_currency_conversion_source VARCHAR(32) NOT NULL DEFAULT 'UNAVAILABLE',
    ADD COLUMN default_currency_conversion_transfer_id BIGINT;

ALTER TABLE transactions
    ADD CONSTRAINT transactions_default_currency_amount_positive
        CHECK (default_currency_amount_minor IS NULL OR default_currency_amount_minor >= 0),
    ADD CONSTRAINT transactions_default_currency_uppercase
        CHECK (default_currency IS NULL OR default_currency = UPPER(default_currency)),
    ADD CONSTRAINT transactions_default_currency_conversion_source_valid
        CHECK (default_currency_conversion_source IN ('SAME_CURRENCY', 'ACTUAL_TRANSFER', 'UNAVAILABLE'));

UPDATE transactions tx
SET default_currency = default_account.currency,
    default_currency_amount_minor = CASE
        WHEN transaction_account.currency = default_account.currency THEN tx.amount_minor
        ELSE NULL
    END,
    default_currency_conversion_source = CASE
        WHEN transaction_account.currency = default_account.currency THEN 'SAME_CURRENCY'
        ELSE 'UNAVAILABLE'
    END
FROM tracking_accounts transaction_account,
     tracking_accounts default_account
WHERE tx.tracking_account_id = transaction_account.id
  AND default_account.user_id = tx.user_id
  AND default_account.is_default = TRUE;
