CREATE TABLE recurring_expense_rules (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tracking_account_id BIGINT NOT NULL REFERENCES tracking_accounts(id) ON DELETE RESTRICT,
    category_id BIGINT NOT NULL REFERENCES expense_categories(id) ON DELETE RESTRICT,
    start_date DATE NOT NULL,
    end_date DATE,
    recurrence_frequency INTEGER NOT NULL,
    recurrence_interval TEXT NOT NULL,
    recurrence_rule TEXT,
    status TEXT NOT NULL,
    generated_until DATE NOT NULL,
    last_generated_at TIMESTAMPTZ,
    amount_minor BIGINT NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT recurring_expense_rules_recurrence_frequency_positive CHECK (recurrence_frequency > 0),
    CONSTRAINT recurring_expense_rules_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT recurring_expense_rules_notes_not_blank CHECK (notes IS NULL OR length(trim(notes)) > 0),
    CONSTRAINT recurring_expense_rules_recurrence_interval_supported CHECK (recurrence_interval IN ('DAY', 'WEEK', 'MONTH')),
    CONSTRAINT recurring_expense_rules_status_supported CHECK (status IN ('ACTIVE', 'ENDED', 'DELETED'))
);

CREATE INDEX recurring_expense_rules_user_id_status_idx ON recurring_expense_rules(user_id, status);
CREATE INDEX recurring_expense_rules_tracking_account_id_idx ON recurring_expense_rules(tracking_account_id);
CREATE INDEX recurring_expense_rules_category_id_idx ON recurring_expense_rules(category_id);

ALTER TABLE expenses
    ADD COLUMN recurring_rule_id BIGINT REFERENCES recurring_expense_rules(id) ON DELETE SET NULL,
    ADD COLUMN recurring_instance_date DATE,
    ADD COLUMN recurring_locked BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE expenses
    ADD CONSTRAINT expenses_recurring_instance_requires_rule CHECK (
        (recurring_rule_id IS NULL AND recurring_instance_date IS NULL)
            OR (recurring_rule_id IS NOT NULL AND recurring_instance_date IS NOT NULL)
    );

CREATE UNIQUE INDEX expenses_recurring_rule_instance_date_idx
    ON expenses(recurring_rule_id, recurring_instance_date)
    WHERE recurring_rule_id IS NOT NULL;

CREATE INDEX expenses_recurring_rule_id_idx ON expenses(recurring_rule_id);

CREATE TABLE recurring_expense_skips (
    id BIGSERIAL PRIMARY KEY,
    recurring_rule_id BIGINT NOT NULL REFERENCES recurring_expense_rules(id) ON DELETE CASCADE,
    recurring_instance_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT recurring_expense_skips_rule_instance_date_unique UNIQUE (recurring_rule_id, recurring_instance_date)
);

CREATE INDEX recurring_expense_skips_recurring_rule_id_idx ON recurring_expense_skips(recurring_rule_id);
