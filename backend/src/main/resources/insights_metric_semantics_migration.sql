-- ============================================================
-- Insights metric semantics: machine-readable unit/scale
-- Run on admindb (pgAdmin / psql). Safe to re-run.
-- ============================================================

ALTER TABLE catalogue_columns
    ADD COLUMN IF NOT EXISTS display_unit VARCHAR(40),
    ADD COLUMN IF NOT EXISTS scale_to_canonical DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS grain_label VARCHAR(40);

COMMENT ON COLUMN catalogue_columns.display_unit IS
    'Insights display unit: currency | percent | count | number | raw';
COMMENT ON COLUMN catalogue_columns.scale_to_canonical IS
    'Multiply raw warehouse value by this to obtain canonical business units';
COMMENT ON COLUMN catalogue_columns.grain_label IS
    'Conservative entity label for COUNT(*) language (sessions, rows, records)';
