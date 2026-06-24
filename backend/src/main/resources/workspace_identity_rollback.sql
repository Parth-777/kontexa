-- ============================================================
-- KONTEXA — Enterprise Identity Architecture
-- workspace_identity_rollback.sql
--
-- ROLLBACK SCRIPT — Phase 1 reversal
--
-- WHEN TO USE:
--   Only run this if Phase 1 tables need to be completely removed
--   because of an error during testing BEFORE any production data
--   has been migrated (Phase 2 has NOT been run).
--
-- PRE-ROLLBACK CHECKLIST (complete all before executing):
--   [ ] Confirm no Phase 2 migration has been run
--   [ ] Confirm old tables (user_credentials, app_users) are intact
--       SELECT COUNT(*) FROM public.user_credentials;
--       SELECT COUNT(*) FROM public.app_users;
--   [ ] Confirm no application is currently using new identity tables
--   [ ] Take a manual backup:
--       pg_dump -t workspaces -t identities -t workspace_memberships
--               -t workspace_connectors -t identity_migration_log
--               -t identity_migration_versions
--               admindb > identity_tables_backup_YYYYMMDD.sql
--
-- BACKUP INSTRUCTIONS:
--   Always run a database backup before any migration or rollback.
--   pg_dump connection string:
--     pg_dump -h <host> -U <user> -d admindb -f backup_YYYYMMDD_HHMM.sql
--   Or use pgAdmin: Tools → Backup → format = Custom
--
-- AFTER ROLLBACK VALIDATION:
--   SELECT COUNT(*) FROM public.user_credentials;    -- must match pre-migration count
--   SELECT COUNT(*) FROM public.app_users;           -- must match pre-migration count
--   SELECT * FROM identity_migration_versions;       -- should fail (table dropped)
-- ============================================================

-- Drop Phase 1 tables in reverse dependency order
DROP TABLE IF EXISTS identity_migration_versions CASCADE;
DROP TABLE IF EXISTS identity_migration_log CASCADE;
DROP TABLE IF EXISTS workspace_connectors CASCADE;
DROP TABLE IF EXISTS workspace_memberships CASCADE;
DROP TABLE IF EXISTS identities CASCADE;
DROP TABLE IF EXISTS workspaces CASCADE;

-- Verify cleanup
DO $$
DECLARE
    remaining_tables TEXT[];
BEGIN
    SELECT ARRAY_AGG(table_name) INTO remaining_tables
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_name IN (
          'workspaces', 'identities', 'workspace_memberships',
          'workspace_connectors', 'identity_migration_log', 'identity_migration_versions'
      );

    IF remaining_tables IS NOT NULL AND ARRAY_LENGTH(remaining_tables, 1) > 0 THEN
        RAISE EXCEPTION 'ROLLBACK INCOMPLETE: tables still exist: %', ARRAY_TO_STRING(remaining_tables, ', ');
    ELSE
        RAISE NOTICE 'ROLLBACK SUCCESSFUL: All Phase 1 identity tables have been removed.';
        RAISE NOTICE 'Legacy tables (user_credentials, app_users) are untouched.';
    END IF;
END;
$$;
