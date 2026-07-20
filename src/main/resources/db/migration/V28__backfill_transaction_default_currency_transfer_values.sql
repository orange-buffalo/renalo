DO $$
DECLARE
    batch_ids BIGINT[];
    last_transaction_id BIGINT := 0;
BEGIN
    LOOP
        SELECT ARRAY(
            SELECT id
            FROM transactions
            WHERE id > last_transaction_id
            ORDER BY id
            LIMIT 500
        ) INTO batch_ids;

        EXIT WHEN CARDINALITY(batch_ids) = 0;

        EXECUTE FORMAT($valuation$
WITH default_accounts AS (
    SELECT user_id, currency
    FROM tracking_accounts
    WHERE is_default = TRUE
),
valuations AS (
    SELECT tx.id,
           default_account.currency AS default_currency,
           CASE
               WHEN transaction_account.currency = default_account.currency THEN tx.amount_minor
               WHEN evidence.id IS NULL THEN NULL
               ELSE ROUND(
                   tx.amount_minor::NUMERIC * evidence.default_amount_minor::NUMERIC
                       / evidence.foreign_amount_minor::NUMERIC
               )::BIGINT
           END AS default_currency_amount_minor,
           CASE
               WHEN transaction_account.currency = default_account.currency THEN 'SAME_CURRENCY'
               WHEN evidence.id IS NULL THEN 'UNAVAILABLE'
               ELSE 'ACTUAL_TRANSFER'
           END AS conversion_source,
           CASE
               WHEN transaction_account.currency = default_account.currency THEN NULL
               ELSE evidence.id
           END AS conversion_transfer_id
    FROM transactions tx
    JOIN tracking_accounts transaction_account ON transaction_account.id = tx.tracking_account_id
    JOIN default_accounts default_account ON default_account.user_id = tx.user_id
    LEFT JOIN LATERAL (
        SELECT transfer.id,
               CASE
                   WHEN source_account.currency = transaction_account.currency THEN transfer.source_amount_minor
                   ELSE transfer.target_amount_minor
               END AS foreign_amount_minor,
               CASE
                   WHEN source_account.currency = default_account.currency THEN transfer.source_amount_minor
                   ELSE transfer.target_amount_minor
               END AS default_amount_minor
        FROM funds_transfers transfer
        JOIN tracking_accounts source_account ON source_account.id = transfer.source_account_id
        JOIN tracking_accounts target_account ON target_account.id = transfer.target_account_id
        WHERE transfer.user_id = tx.user_id
          AND (
              (source_account.currency = transaction_account.currency AND target_account.currency = default_account.currency)
              OR
              (source_account.currency = default_account.currency AND target_account.currency = transaction_account.currency)
          )
        ORDER BY
            CASE
                WHEN tx.type = 'INCOME'
                    AND source_account.currency = transaction_account.currency
                    AND transfer.date >= tx.date THEN 0
                WHEN tx.type = 'EXPENSE'
                    AND target_account.currency = transaction_account.currency
                    AND transfer.date <= tx.date THEN 0
                WHEN tx.type = 'INCOME' AND source_account.currency = transaction_account.currency THEN 1
                WHEN tx.type = 'EXPENSE' AND target_account.currency = transaction_account.currency THEN 1
                ELSE 2
            END,
            CASE
                WHEN source_account.currency = transaction_account.currency
                    AND transfer.source_account_id = tx.tracking_account_id THEN 0
                WHEN target_account.currency = transaction_account.currency
                    AND transfer.target_account_id = tx.tracking_account_id THEN 0
                ELSE 1
            END,
            CASE
                WHEN source_account.currency = transaction_account.currency
                    AND transfer.source_amount_minor = tx.amount_minor THEN 0
                WHEN target_account.currency = transaction_account.currency
                    AND transfer.target_amount_minor = tx.amount_minor THEN 0
                ELSE 1
            END,
            ABS(transfer.date - tx.date),
            transfer.id
        LIMIT 1
    ) evidence ON transaction_account.currency <> default_account.currency
    WHERE tx.id = ANY (%L::BIGINT[])
)
UPDATE transactions tx
SET default_currency_amount_minor = valuations.default_currency_amount_minor,
    default_currency = valuations.default_currency,
    default_currency_conversion_source = valuations.conversion_source,
    default_currency_conversion_transfer_id = valuations.conversion_transfer_id
FROM valuations
WHERE tx.id = valuations.id
$valuation$, batch_ids::TEXT);

        last_transaction_id := batch_ids[CARDINALITY(batch_ids)];
    END LOOP;
END
$$;
