ALTER TABLE transactions
    DROP CONSTRAINT expenses_category_id_fkey;

ALTER TABLE recurring_transaction_rules
    DROP CONSTRAINT recurring_expense_rules_category_id_fkey;
