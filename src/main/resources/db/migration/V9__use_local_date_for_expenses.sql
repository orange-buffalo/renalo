DROP INDEX expenses_user_id_date_time_idx;

ALTER TABLE expenses
    RENAME COLUMN date_time TO date;

ALTER TABLE expenses
    ALTER COLUMN date TYPE DATE USING (date AT TIME ZONE 'UTC')::DATE;

CREATE INDEX expenses_user_id_date_idx ON expenses(user_id, date DESC);
