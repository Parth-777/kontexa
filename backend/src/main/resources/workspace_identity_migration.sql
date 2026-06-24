-- ============================================================
-- KONTEXA — Enterprise Identity Architecture
-- workspace_identity_migration.sql
--
-- Phase 1: Introduce workspace-scoped identity tables.
--
-- SAFETY CONTRACT:
--   This script creates NEW tables only.
--   It does NOT drop, truncate, or alter existing tables.
--   user_credentials and app_users remain untouched.
--   Safe to run against production.
--
-- Run order: execute this entire file once in pgAdmin Query Tool
--            against the admindb database.
-- ============================================================


-- ═══════════════════════════════════════════════════════════
-- WORKSPACES
-- The primary organisational boundary.
-- All platform resources (connectors, catalogues, chats,
-- findings, insights) are scoped by workspace_id.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS workspaces (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(255) NOT NULL,
    slug                    VARCHAR(128) NOT NULL UNIQUE,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | SUSPENDED | PROVISIONING
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    identity_provider_type  VARCHAR(64)  NOT NULL DEFAULT 'LOCAL_PASSWORD',
        -- LOCAL_PASSWORD | OKTA | AZURE_AD | SAML | GOOGLE_WORKSPACE
    sso_config              TEXT         -- JSON; null until SSO is configured
);


-- ═══════════════════════════════════════════════════════════
-- IDENTITIES
-- Unified identity record. Replaces both user_credentials
-- and app_users. Admins and users are the same entity type;
-- their role is stored in workspace_memberships.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS identities (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    email             VARCHAR(320) NOT NULL,
    display_name      VARCHAR(255),
    auth_provider     VARCHAR(64)  NOT NULL DEFAULT 'LOCAL_PASSWORD',
        -- LOCAL_PASSWORD | OKTA | AZURE_AD | SAML | GOOGLE_WORKSPACE
    password_hash     TEXT,
        -- Null for SSO-only identities.
        -- Stores plain-text during transition period; BCrypt-ready by column name.
    status            VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | SUSPENDED | PENDING_INVITE
    last_login_at     TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- Migration provenance — preserved for auditability
    migrated_from     VARCHAR(64),
        -- 'user_credentials' | 'app_users' | NULL (natively created)
    legacy_user_id    VARCHAR(255),
        -- Original user_id from the old table; null for native identities

    UNIQUE (workspace_id, email)
);


-- ═══════════════════════════════════════════════════════════
-- WORKSPACE MEMBERSHIPS
-- Maps identities to workspaces with an explicit role.
-- OWNER: full workspace control (one per workspace).
-- ADMIN: user management, connector config.
-- ANALYST: can query, cannot manage users.
-- VIEWER: read-only.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS workspace_memberships (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    identity_id   UUID        NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    role          VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
        -- OWNER | ADMIN | ANALYST | VIEWER
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, identity_id)
);


-- ═══════════════════════════════════════════════════════════
-- WORKSPACE CONNECTORS
-- Workspace-scoped warehouse connectors.
-- Migrated from cloud_db_link in user_credentials.
-- One connector per warehouse type per workspace.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS workspace_connectors (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    warehouse_type        VARCHAR(32)  NOT NULL,
        -- BIGQUERY | SNOWFLAKE | REDSHIFT
    encrypted_credentials TEXT         NOT NULL,
        -- JSON blob; same structure as current cloud_db_link
    connection_metadata   TEXT,
        -- JSON: projectId, dataset, location, account, etc. (non-secret portion)
    status                VARCHAR(32)  NOT NULL DEFAULT 'CONNECTED',
        -- CONNECTED | DISCONNECTED | ERROR
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (workspace_id, warehouse_type)
);


-- ═══════════════════════════════════════════════════════════
-- IDENTITY MIGRATION LOG
-- Records every record migrated from old tables to new ones.
-- Used for auditability, validation, and rollback capability.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS identity_migration_log (
    id                    BIGSERIAL    PRIMARY KEY,
    migration_phase       VARCHAR(64)  NOT NULL,
        -- 'PHASE_2_TENANT' | 'PHASE_2_USER' | 'PHASE_2_CONNECTOR'
    source_table          VARCHAR(64)  NOT NULL,
        -- 'user_credentials' | 'app_users'
    source_id             VARCHAR(255) NOT NULL,
        -- The user_id from the source table
    target_workspace_id   UUID,
    target_identity_id    UUID,
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
        -- PENDING | COMPLETED | FAILED | ROLLED_BACK
    notes                 TEXT,
    migrated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);


-- ═══════════════════════════════════════════════════════════
-- IDENTITY MIGRATION VERSIONS
-- Tracks which migration phases have been applied.
-- Provides rollback_sql for each phase.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS identity_migration_versions (
    version               VARCHAR(64)  PRIMARY KEY,
    description           TEXT         NOT NULL,
    applied_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    applied_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    rollback_sql          TEXT         -- SQL to undo this version if needed
);


-- ── Record Phase 1 ───────────────────────────────────────────────────────────
INSERT INTO identity_migration_versions (version, description, rollback_sql)
VALUES (
    'IDENTITY_V1_SCHEMA',
    'Phase 1: Created workspaces, identities, workspace_memberships, workspace_connectors, identity_migration_log, identity_migration_versions tables',
    $rollback$
        -- ROLLBACK FOR IDENTITY_V1_SCHEMA
        -- Run only after verifying old system (user_credentials, app_users) still intact
        DROP TABLE IF EXISTS identity_migration_versions CASCADE;
        DROP TABLE IF EXISTS identity_migration_log CASCADE;
        DROP TABLE IF EXISTS workspace_connectors CASCADE;
        DROP TABLE IF EXISTS workspace_memberships CASCADE;
        DROP TABLE IF EXISTS identities CASCADE;
        DROP TABLE IF EXISTS workspaces CASCADE;
    $rollback$
)
ON CONFLICT (version) DO NOTHING;


-- ── Indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_workspaces_slug
    ON workspaces(slug);

CREATE INDEX IF NOT EXISTS idx_workspaces_status
    ON workspaces(status);

CREATE INDEX IF NOT EXISTS idx_identities_workspace
    ON identities(workspace_id);

CREATE INDEX IF NOT EXISTS idx_identities_email
    ON identities(LOWER(email));

CREATE INDEX IF NOT EXISTS idx_identities_legacy_user_id
    ON identities(legacy_user_id)
    WHERE legacy_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identities_status
    ON identities(status);

CREATE INDEX IF NOT EXISTS idx_memberships_workspace
    ON workspace_memberships(workspace_id);

CREATE INDEX IF NOT EXISTS idx_memberships_identity
    ON workspace_memberships(identity_id);

CREATE INDEX IF NOT EXISTS idx_memberships_role
    ON workspace_memberships(workspace_id, role);

CREATE INDEX IF NOT EXISTS idx_connectors_workspace
    ON workspace_connectors(workspace_id);

CREATE INDEX IF NOT EXISTS idx_connectors_status
    ON workspace_connectors(workspace_id, status);

CREATE INDEX IF NOT EXISTS idx_migration_log_source
    ON identity_migration_log(source_table, source_id);

CREATE INDEX IF NOT EXISTS idx_migration_log_status
    ON identity_migration_log(status);

CREATE INDEX IF NOT EXISTS idx_migration_log_workspace
    ON identity_migration_log(target_workspace_id)
    WHERE target_workspace_id IS NOT NULL;


-- ── Verification query (run after to confirm tables were created) ─────────────
-- SELECT table_name, pg_size_pretty(pg_total_relation_size(quote_ident(table_name)))
-- FROM information_schema.tables
-- WHERE table_schema = 'public'
--   AND table_name IN ('workspaces','identities','workspace_memberships',
--                      'workspace_connectors','identity_migration_log','identity_migration_versions')
-- ORDER BY table_name;
