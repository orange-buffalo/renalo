ALTER TABLE account_adjustments
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
