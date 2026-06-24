package com.example.BACKEND.identity.invite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InviteRepository {

    private final JdbcTemplate jdbc;

    public InviteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isEnabled() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='invite_tokens'",
                Integer.class);
        return n != null && n > 0;
    }

    public UUID insertToken(
            UUID workspaceId,
            UUID identityId,
            String tokenHash,
            UUID invitedBy,
            String role,
            LocalDateTime expiresAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invite_tokens
                    (id, workspace_id, identity_id, token_hash, invited_by, role, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, workspaceId, identityId, tokenHash, invitedBy, role, expiresAt);
        return id;
    }

    public void revokePendingTokensForIdentity(UUID identityId) {
        jdbc.update("""
                UPDATE invite_tokens
                SET revoked_at = NOW()
                WHERE identity_id = ?
                  AND accepted_at IS NULL
                  AND revoked_at IS NULL
                """, identityId);
    }

    public void markAccepted(UUID inviteId) {
        jdbc.update("UPDATE invite_tokens SET accepted_at = NOW() WHERE id = ?", inviteId);
    }

    public void markRevoked(UUID inviteId) {
        jdbc.update("""
                UPDATE invite_tokens
                SET revoked_at = NOW()
                WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """, inviteId);
    }

    public Optional<Map<String, Object>> findValidByTokenHash(String tokenHash) {
        var rows = jdbc.queryForList("""
                SELECT t.id, t.workspace_id, t.identity_id, t.role, t.expires_at,
                       i.email, i.display_name,
                       w.slug, w.name AS workspace_name,
                       inv.display_name AS inviter_name
                FROM invite_tokens t
                JOIN identities i ON i.id = t.identity_id
                JOIN workspaces w ON w.id = t.workspace_id
                LEFT JOIN identities inv ON inv.id = t.invited_by
                WHERE t.token_hash = ?
                  AND t.accepted_at IS NULL
                  AND t.revoked_at IS NULL
                  AND t.expires_at > NOW()
                  AND i.status = 'INVITED'
                LIMIT 1
                """, tokenHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findById(UUID inviteId, UUID workspaceId) {
        var rows = jdbc.queryForList("""
                SELECT t.id, t.workspace_id, t.identity_id, t.role, t.expires_at,
                       t.accepted_at, t.revoked_at, t.created_at,
                       i.email, i.display_name, i.status AS identity_status
                FROM invite_tokens t
                JOIN identities i ON i.id = t.identity_id
                WHERE t.id = ? AND t.workspace_id = ?
                LIMIT 1
                """, inviteId, workspaceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listTeamWithInvites(UUID workspaceId) {
        return jdbc.queryForList("""
                SELECT i.id AS identity_id, i.email, i.display_name, i.status AS identity_status,
                       COALESCE(m.role, t.role, 'VIEWER') AS role,
                       t.id AS invite_id, t.expires_at, t.revoked_at, t.accepted_at,
                       t.created_at AS invited_at,
                       inv.display_name AS invited_by_name
                FROM identities i
                LEFT JOIN workspace_memberships m
                    ON m.identity_id = i.id AND m.workspace_id = ?
                LEFT JOIN LATERAL (
                    SELECT it.*
                    FROM invite_tokens it
                    WHERE it.identity_id = i.id AND it.workspace_id = ?
                    ORDER BY it.created_at DESC
                    LIMIT 1
                ) t ON true
                LEFT JOIN identities inv ON inv.id = t.invited_by
                WHERE i.workspace_id = ?
                  AND (m.identity_id IS NOT NULL OR i.status IN ('INVITED', 'REVOKED'))
                ORDER BY
                    CASE COALESCE(m.role, t.role, 'VIEWER')
                        WHEN 'OWNER'   THEN 1
                        WHEN 'ADMIN'   THEN 2
                        WHEN 'ANALYST' THEN 3
                        ELSE 4
                    END,
                    i.email
                """, workspaceId, workspaceId, workspaceId);
    }
}
