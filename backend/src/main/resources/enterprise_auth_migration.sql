-- ============================================================
-- KONTEXA — Enterprise Auth Layer (Phase 2)
-- enterprise_auth_migration.sql
--
-- Adds: sessions, invites, password reset, audit trail.
-- Prepares multi-workspace identity (email index for switcher).
--
-- SAFE: does not drop user_credentials or app_users.
-- Run after workspace_identity_migration.sql in pgAdmin.
-- ============================================================


-- ═══════════════════════════════════════════════════════════
-- AUTH SESSIONS
-- Access + refresh tokens with device metadata and expiry.
-- active_workspace_id = current workspace context for switcher.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS auth_sessions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id           UUID         NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    active_workspace_id   UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    access_token_hash     VARCHAR(128) NOT NULL UNIQUE,
    refresh_token_hash    VARCHAR(128) NOT NULL UNIQUE,
    device_label          VARCHAR(255),
    user_agent            TEXT,
    ip_address            VARCHAR(64),
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    access_expires_at     TIMESTAMP    NOT NULL,
    refresh_expires_at    TIMESTAMP    NOT NULL,
    revoked_at            TIMESTAMP,
    last_used_at          TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_sessions_identity
    ON auth_sessions(identity_id) WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_auth_sessions_refresh
    ON auth_sessions(refresh_token_hash) WHERE revoked_at IS NULL;


-- ═══════════════════════════════════════════════════════════
-- WORKSPACE INVITES (invite-only onboarding)
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS workspace_invites (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'VIEWER',
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    invited_by      UUID         REFERENCES identities(id) ON DELETE SET NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
        -- PENDING | ACCEPTED | EXPIRED | REVOKED
    expires_at      TIMESTAMP    NOT NULL,
    accepted_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workspace_invites_email
    ON workspace_invites(LOWER(email)) WHERE status = 'PENDING';


-- ═══════════════════════════════════════════════════════════
-- PASSWORD RESET TOKENS
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_id     UUID         NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMP    NOT NULL,
    used_at         TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_identity
    ON password_reset_tokens(identity_id) WHERE used_at IS NULL;


-- ═══════════════════════════════════════════════════════════
-- AUTH AUDIT EVENTS
-- login, failed_login, connector_change, role_change, invite_accept
-- ═══════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS auth_audit_events (
    id              BIGSERIAL    PRIMARY KEY,
    workspace_id    UUID         REFERENCES workspaces(id) ON DELETE SET NULL,
    identity_id     UUID         REFERENCES identities(id) ON DELETE SET NULL,
    event_type      VARCHAR(64)  NOT NULL,
    event_detail    TEXT,
    ip_address      VARCHAR(64),
    user_agent      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_audit_workspace
    ON auth_audit_events(workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_audit_identity
    ON auth_audit_events(identity_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_auth_audit_type
    ON auth_audit_events(event_type, created_at DESC);


-- ═══════════════════════════════════════════════════════════
-- MULTI-WORKSPACE: index for email-based workspace discovery
-- (supports workspace switcher; does not remove workspace_id column yet)
-- ═══════════════════════════════════════════════════════════
CREATE INDEX IF NOT EXISTS idx_identities_email_lower
    ON identities(LOWER(email));


-- Record migration version
INSERT INTO identity_migration_versions (version, description, rollback_sql)
VALUES (
    'ENTERPRISE_AUTH_V1',
    'Phase 2 enterprise auth: auth_sessions, workspace_invites, password_reset_tokens, auth_audit_events',
    $rollback$
        DROP TABLE IF EXISTS auth_audit_events CASCADE;
        DROP TABLE IF EXISTS password_reset_tokens CASCADE;
        DROP TABLE IF EXISTS workspace_invites CASCADE;
        DROP TABLE IF EXISTS auth_sessions CASCADE;
        DROP INDEX IF EXISTS idx_identities_email_lower;
    $rollback$
)
ON CONFLICT (version) DO NOTHING;
