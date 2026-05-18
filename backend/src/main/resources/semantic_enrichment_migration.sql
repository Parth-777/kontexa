-- ============================================================
-- Semantic Enrichment Migration
-- Run this in pgAdmin Query Tool on your admindb database
-- Adds 4 AI-generated semantic fields to catalogue_columns
-- ============================================================

ALTER TABLE catalogue_columns
    ADD COLUMN IF NOT EXISTS aggregation_method  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS business_meaning    TEXT,
    ADD COLUMN IF NOT EXISTS comparison_period   VARCHAR(10),
    ADD COLUMN IF NOT EXISTS date_granularity    VARCHAR(20);

-- Optional: index on aggregation_method for fast filtering
CREATE INDEX IF NOT EXISTS idx_col_aggregation
    ON catalogue_columns(aggregation_method);
