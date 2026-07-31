-- ============================================================
-- KONTEXA — Phase A Tenancy Foundation (NOTES ONLY)
-- phase_a_tenancy_foundation_notes.sql
--
-- Phase A does NOT require destructive schema changes.
-- auth_sessions already stores:
--   identity_id          → userId
--   active_workspace_id  → tenantId
--
-- Domain mapping (no rename in Phase A):
--   Tenant      ↔ workspaces
--   User        ↔ identities
--   Membership  ↔ workspace_memberships
--   tenantId    ↔ workspace_id / active_workspace_id
--   userId      ↔ identity_id
--
-- Future phases will add UUID tenant_id columns to catalogue /
-- connector / run tables and make identities globally unique.
-- ============================================================

-- Verification (optional — read only):
-- SELECT column_name FROM information_schema.columns
-- WHERE table_name = 'auth_sessions'
--   AND column_name IN ('identity_id', 'active_workspace_id');

INSERT INTO identity_migration_versions (version, description, rollback_sql)
VALUES (
    'TENANCY_PHASE_A_CONTEXT',
    'Phase A: TenantContext resolved at auth; session binds tenantId+userId; membership repos require tenantId. No DDL.',
    $rollback$
        -- No DDL applied in Phase A; remove this version row only if needed.
        DELETE FROM identity_migration_versions WHERE version = 'TENANCY_PHASE_A_CONTEXT';
    $rollback$
)
ON CONFLICT (version) DO NOTHING;
