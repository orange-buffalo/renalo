ALTER TABLE expenses RENAME TO transactions;

ALTER TABLE transactions
    ADD COLUMN type TEXT NOT NULL DEFAULT 'EXPENSE',
    ADD CONSTRAINT transactions_type_supported CHECK (type IN ('INCOME', 'EXPENSE'));

ALTER INDEX expenses_user_id_date_idx RENAME TO transactions_user_id_date_idx;
ALTER INDEX expenses_tracking_account_id_idx RENAME TO transactions_tracking_account_id_idx;
ALTER INDEX expenses_category_id_idx RENAME TO transactions_category_id_idx;
ALTER INDEX expenses_recurring_rule_instance_date_idx RENAME TO transactions_recurring_rule_instance_date_idx;
ALTER INDEX expenses_recurring_rule_id_idx RENAME TO transactions_recurring_rule_id_idx;

ALTER TABLE recurring_expense_rules RENAME TO recurring_transaction_rules;

ALTER TABLE recurring_transaction_rules
    ADD COLUMN transaction_type TEXT NOT NULL DEFAULT 'EXPENSE',
    ADD CONSTRAINT recurring_transaction_rules_transaction_type_supported CHECK (transaction_type IN ('INCOME', 'EXPENSE'));

ALTER INDEX recurring_expense_rules_user_id_status_idx RENAME TO recurring_transaction_rules_user_id_status_idx;
ALTER INDEX recurring_expense_rules_tracking_account_id_idx RENAME TO recurring_transaction_rules_tracking_account_id_idx;
ALTER INDEX recurring_expense_rules_category_id_idx RENAME TO recurring_transaction_rules_category_id_idx;

ALTER TABLE recurring_expense_skips RENAME TO recurring_transaction_skips;

ALTER INDEX recurring_expense_skips_recurring_rule_id_idx RENAME TO recurring_transaction_skips_recurring_rule_id_idx;
