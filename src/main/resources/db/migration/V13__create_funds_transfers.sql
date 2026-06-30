CREATE TABLE funds_transfers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_account_id BIGINT NOT NULL REFERENCES tracking_accounts(id) ON DELETE RESTRICT,
    target_account_id BIGINT NOT NULL REFERENCES tracking_accounts(id) ON DELETE RESTRICT,
    source_amount_minor BIGINT NOT NULL,
    target_amount_minor BIGINT NOT NULL,
    date DATE NOT NULL,
    CONSTRAINT funds_transfers_source_amount_positive CHECK (source_amount_minor > 0),
    CONSTRAINT funds_transfers_target_amount_positive CHECK (target_amount_minor > 0),
    CONSTRAINT funds_transfers_different_accounts CHECK (source_account_id <> target_account_id)
);

CREATE INDEX funds_transfers_user_id_date_idx ON funds_transfers(user_id, date DESC);
CREATE INDEX funds_transfers_source_account_id_idx ON funds_transfers(source_account_id);
CREATE INDEX funds_transfers_target_account_id_idx ON funds_transfers(target_account_id);
