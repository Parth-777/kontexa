-- ═══════════════════════════════════════════════════════════
-- INVITE TOKENS — enterprise invitation onboarding
-- Run in pgAdmin against admindb after workspace_identity + enterprise_auth migrations.
-- ═══════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS invite_tokens (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID         NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    identity_id     UUID         NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    invited_by      UUID         REFERENCES identities(id) ON DELETE SET NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'VIEWER',
    expires_at      TIMESTAMP    NOT NULL,
    accepted_at     TIMESTAMP,
    revoked_at      TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invite_tokens_identity_pending
    ON invite_tokens(identity_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_invite_tokens_workspace_pending
    ON invite_tokens(workspace_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Normalize legacy pending status to INVITED
UPDATE identities SET status = 'INVITED' WHERE status = 'PENDING_INVITE';
