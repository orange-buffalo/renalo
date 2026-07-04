ALTER TABLE account_adjustments ADD COLUMN date DATE;
UPDATE account_adjustments SET date = created_at::date;
ALTER TABLE account_adjustments ALTER COLUMN date SET NOT NULL;
