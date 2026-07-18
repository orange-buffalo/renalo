CREATE INDEX transactions_user_type_category_idx
    ON transactions(user_id, type, category_id);
CREATE INDEX transactions_user_tracking_account_idx
    ON transactions(user_id, tracking_account_id);
CREATE INDEX funds_transfers_user_source_account_idx
    ON funds_transfers(user_id, source_account_id);
CREATE INDEX funds_transfers_user_target_account_idx
    ON funds_transfers(user_id, target_account_id);
CREATE INDEX account_adjustments_user_tracking_account_idx
    ON account_adjustments(user_id, tracking_account_id);
