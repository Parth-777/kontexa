-- Phase B rollback (optional): drop UUID tenant_id columns added by phase_b_tenant_id_migration.sql
-- Does NOT delete workspace_connectors rows (use identity migration rollback for that).

ALTER TABLE client_catalogues DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE catalogue_snapshots DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE insight_cards DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE agent_reports DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE agent_runs DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE signals DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE decision_memory DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE daily_metric_rollups DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE tenant_business_profiles DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE catalogue_table_relations DROP COLUMN IF EXISTS tenant_id;
ALTER TABLE app_users DROP COLUMN IF EXISTS workspace_id;

DELETE FROM identity_migration_versions WHERE version = 'phase_b_tenant_id';
