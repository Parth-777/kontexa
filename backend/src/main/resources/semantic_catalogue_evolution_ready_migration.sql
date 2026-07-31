-- ============================================================
-- Semantic catalogue Step 1 — additive, versioning-ready
-- Run on admindb. Safe to re-run (IF NOT EXISTS).
--
-- Design notes:
--  - Relational client_catalogues / catalogue_columns remain the
--    source of truth for each catalogue version (row = version).
--  - Snapshot JSON stays a derived published projection.
--  - lineage_key + supersedes_catalogue_id prepare future evolution
--    without another large schema redesign.
--  - business_meaning already exists; it is the single semantic SoT
--    (rich explanation). description stays the short summary.
-- ============================================================

ALTER TABLE client_catalogues
    ADD COLUMN IF NOT EXISTS lineage_key TEXT,
    ADD COLUMN IF NOT EXISTS supersedes_catalogue_id BIGINT,
    ADD COLUMN IF NOT EXISTS business_profile TEXT,
    ADD COLUMN IF NOT EXISTS global_caveats TEXT,
    ADD COLUMN IF NOT EXISTS entities_json TEXT;

CREATE INDEX IF NOT EXISTS idx_client_catalogues_lineage_key
    ON client_catalogues(client_id, lineage_key);

ALTER TABLE catalogue_columns
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION;

-- Optional comment for operators (Postgres)
COMMENT ON COLUMN catalogue_columns.business_meaning IS
    'Primary semantic knowledge for LLM reasoning (rich explanation). description is short summary only.';
COMMENT ON COLUMN client_catalogues.lineage_key IS
    'Stable key across rediscovery versions; null until evolution assigns one.';
COMMENT ON COLUMN client_catalogues.supersedes_catalogue_id IS
    'Prior catalogue id in the same lineage; null for first version.';
