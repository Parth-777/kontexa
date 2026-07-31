-- =============================================================================
-- Phase B: UUID tenant_id ownership + connector cutover prep
-- Run manually in pgAdmin / psql after Phase A identity tables exist.
-- Safe / idempotent: uses IF NOT EXISTS / WHERE tenant_id IS NULL.
-- =============================================================================

-- Record migration version (optional ops table)
INSERT INTO identity_migration_versions (version, description)
SELECT 'phase_b_tenant_id', 'Add UUID tenant_id to product tables; backfill from workspaces.slug'
WHERE EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'identity_migration_versions'
)
AND NOT EXISTS (
    SELECT 1 FROM identity_migration_versions WHERE version = 'phase_b_tenant_id'
);

-- ── Helper: add tenant_id column if missing ───────────────────────────────────
-- Applied per table below.

-- ── client_catalogues ─────────────────────────────────────────────────────────
ALTER TABLE client_catalogues
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_client_catalogues_tenant_id
    ON client_catalogues (tenant_id);

UPDATE client_catalogues c
SET tenant_id = w.id
FROM workspaces w
WHERE c.tenant_id IS NULL
  AND (
      LOWER(c.client_id) = LOWER(w.slug)
      OR LOWER(c.schema_name) = LOWER(w.slug)
  );

-- ── catalogue_snapshots ───────────────────────────────────────────────────────
ALTER TABLE catalogue_snapshots
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_catalogue_snapshots_tenant_id
    ON catalogue_snapshots (tenant_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_catalogue_snapshots_tenant_id
    ON catalogue_snapshots (tenant_id)
    WHERE tenant_id IS NOT NULL;

UPDATE catalogue_snapshots s
SET tenant_id = w.id
FROM workspaces w
WHERE s.tenant_id IS NULL
  AND LOWER(s.client_id) = LOWER(w.slug);

-- ── insight_cards ─────────────────────────────────────────────────────────────
ALTER TABLE insight_cards
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_insight_cards_tenant_id
    ON insight_cards (tenant_id);

UPDATE insight_cards i
SET tenant_id = w.id
FROM workspaces w
WHERE i.tenant_id IS NULL
  AND LOWER(i.client_id) = LOWER(w.slug);

-- ── agent_reports ─────────────────────────────────────────────────────────────
ALTER TABLE agent_reports
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_agent_reports_tenant_id
    ON agent_reports (tenant_id);

UPDATE agent_reports r
SET tenant_id = w.id
FROM workspaces w
WHERE r.tenant_id IS NULL
  AND LOWER(r.client_id) = LOWER(w.slug);

-- ── agent_runs ────────────────────────────────────────────────────────────────
ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_agent_runs_tenant_id
    ON agent_runs (tenant_id);

UPDATE agent_runs r
SET tenant_id = w.id
FROM workspaces w
WHERE r.tenant_id IS NULL
  AND LOWER(r.client_id) = LOWER(w.slug);

-- ── signals ───────────────────────────────────────────────────────────────────
ALTER TABLE signals
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_signals_tenant_id
    ON signals (tenant_id);

UPDATE signals s
SET tenant_id = w.id
FROM workspaces w
WHERE s.tenant_id IS NULL
  AND LOWER(s.client_id) = LOWER(w.slug);

-- ── decision_memory ───────────────────────────────────────────────────────────
ALTER TABLE decision_memory
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_decision_memory_tenant_id
    ON decision_memory (tenant_id);

UPDATE decision_memory d
SET tenant_id = w.id
FROM workspaces w
WHERE d.tenant_id IS NULL
  AND LOWER(d.client_id) = LOWER(w.slug);

-- ── daily_metric_rollups ──────────────────────────────────────────────────────
ALTER TABLE daily_metric_rollups
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_daily_metric_rollups_tenant_id
    ON daily_metric_rollups (tenant_id);

UPDATE daily_metric_rollups r
SET tenant_id = w.id
FROM workspaces w
WHERE r.tenant_id IS NULL
  AND LOWER(r.client_id) = LOWER(w.slug);

-- ── tenant_business_profiles ──────────────────────────────────────────────────
ALTER TABLE tenant_business_profiles
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_tenant_business_profiles_tenant_id
    ON tenant_business_profiles (tenant_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_business_profiles_tenant_id
    ON tenant_business_profiles (tenant_id)
    WHERE tenant_id IS NOT NULL;

UPDATE tenant_business_profiles p
SET tenant_id = w.id
FROM workspaces w
WHERE p.tenant_id IS NULL
  AND LOWER(p.client_id) = LOWER(w.slug);

-- ── catalogue_table_relations ─────────────────────────────────────────────────
ALTER TABLE catalogue_table_relations
    ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE INDEX IF NOT EXISTS idx_catalogue_table_relations_tenant_id
    ON catalogue_table_relations (tenant_id);

UPDATE catalogue_table_relations r
SET tenant_id = w.id
FROM workspaces w
WHERE r.tenant_id IS NULL
  AND LOWER(r.client_id) = LOWER(w.slug);

-- ── app_users: add UUID workspace_id if missing (legacy string tenant_id kept) ─
ALTER TABLE app_users
    ADD COLUMN IF NOT EXISTS workspace_id UUID;

CREATE INDEX IF NOT EXISTS idx_app_users_workspace_id
    ON app_users (workspace_id);

UPDATE app_users u
SET workspace_id = w.id
FROM workspaces w
WHERE u.workspace_id IS NULL
  AND LOWER(u.tenant_id) = LOWER(w.slug);

-- ── Connector cutover: copy remaining cloud_db_link → workspace_connectors ────
-- Idempotent upsert by (workspace_id, warehouse_type).
INSERT INTO workspace_connectors (id, workspace_id, warehouse_type, encrypted_credentials, status, created_at, updated_at)
SELECT
    gen_random_uuid(),
    w.id,
    CASE
        WHEN LOWER(uc.cloud_db_link) LIKE '%snowflake%' THEN 'SNOWFLAKE'
        WHEN LOWER(uc.cloud_db_link) LIKE '%bigquery%'  THEN 'BIGQUERY'
        ELSE 'BIGQUERY'
    END,
    uc.cloud_db_link,
    'CONNECTED',
    NOW(),
    NOW()
FROM user_credentials uc
JOIN workspaces w ON LOWER(w.slug) = LOWER(uc.user_id)
WHERE uc.cloud_db_link IS NOT NULL
  AND TRIM(uc.cloud_db_link) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM workspace_connectors wc
      WHERE wc.workspace_id = w.id
        AND wc.warehouse_type = CASE
            WHEN LOWER(uc.cloud_db_link) LIKE '%snowflake%' THEN 'SNOWFLAKE'
            WHEN LOWER(uc.cloud_db_link) LIKE '%bigquery%'  THEN 'BIGQUERY'
            ELSE 'BIGQUERY'
        END
  );

-- Verification helpers (optional — inspect after run):
-- SELECT COUNT(*) AS unmapped_catalogues FROM client_catalogues WHERE tenant_id IS NULL;
-- SELECT COUNT(*) AS connectors FROM workspace_connectors;
