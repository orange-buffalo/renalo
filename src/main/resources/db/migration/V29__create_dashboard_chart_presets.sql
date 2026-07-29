CREATE TABLE dashboard_chart_presets (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    category_filter_mode VARCHAR(20) NOT NULL,
    category_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    account_filter_mode VARCHAR(20) NOT NULL,
    account_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    granularity VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    is_active BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT dashboard_chart_presets_name_not_blank CHECK (length(trim(name)) > 0),
    CONSTRAINT dashboard_chart_presets_transaction_type_supported
        CHECK (transaction_type IN ('EXPENSE', 'INCOME')),
    CONSTRAINT dashboard_chart_presets_category_filter_mode_supported
        CHECK (category_filter_mode IN ('INCLUDE', 'EXCLUDE')),
    CONSTRAINT dashboard_chart_presets_account_filter_mode_supported
        CHECK (account_filter_mode IN ('INCLUDE', 'EXCLUDE')),
    CONSTRAINT dashboard_chart_presets_granularity_supported
        CHECK (granularity IN ('AUTO', 'DAY', 'WEEK', 'MONTH')),
    CONSTRAINT dashboard_chart_presets_category_ids_array
        CHECK (jsonb_typeof(category_ids) = 'array'),
    CONSTRAINT dashboard_chart_presets_account_ids_array
        CHECK (jsonb_typeof(account_ids) = 'array')
);

CREATE INDEX dashboard_chart_presets_user_type_idx
    ON dashboard_chart_presets (user_id, transaction_type);

CREATE UNIQUE INDEX dashboard_chart_presets_single_active_per_chart
    ON dashboard_chart_presets (user_id, transaction_type)
    WHERE is_active;
