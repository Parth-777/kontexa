package com.example.BACKEND.identity.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean sessionsTableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='auth_sessions'",
                Integer.class);
        return n != null && n > 0;
    }

    public UUID createSession(
            UUID identityId,
            UUID workspaceId,
            String accessHash,
            String refreshHash,
            String deviceLabel,
            String userAgent,
            String ipAddress,
            LocalDateTime accessExpires,
            LocalDateTime refreshExpires
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_sessions
                    (id, identity_id, active_workspace_id, access_token_hash, refresh_token_hash,
                     device_label, user_agent, ip_address, access_expires_at, refresh_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, identityId, workspaceId, accessHash, refreshHash,
                deviceLabel, userAgent, ipAddress,
                Timestamp.valueOf(accessExpires), Timestamp.valueOf(refreshExpires));
        return id;
    }

    public Optional<AuthSessionRecord> findByAccessTokenHash(String hash) {
        return queryOne("access_token_hash", hash);
    }

    public Optional<AuthSessionRecord> findByRefreshTokenHash(String hash) {
        return queryOne("refresh_token_hash", hash);
    }

    public Optional<AuthSessionRecord> findBySessionId(UUID sessionId) {
        List<AuthSessionRecord> rows = jdbc.query(
                "SELECT * FROM auth_sessions WHERE id = ? AND revoked_at IS NULL LIMIT 1",
                MAPPER, sessionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void touchSession(UUID sessionId) {
        jdbc.update("UPDATE auth_sessions SET last_used_at = NOW() WHERE id = ?", sessionId);
    }

    public void revokeSession(UUID sessionId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = NOW() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    public void revokeAllForIdentity(UUID identityId) {
        jdbc.update("UPDATE auth_sessions SET revoked_at = NOW() WHERE identity_id = ? AND revoked_at IS NULL", identityId);
    }

    public void updateActiveWorkspace(UUID sessionId, UUID workspaceId, String newAccessHash, LocalDateTime accessExpires) {
        jdbc.update("""
                UPDATE auth_sessions
                SET active_workspace_id = ?, access_token_hash = ?, access_expires_at = ?, last_used_at = NOW()
                WHERE id = ? AND revoked_at IS NULL
                """,
                workspaceId, newAccessHash, Timestamp.valueOf(accessExpires), sessionId);
    }

    private Optional<AuthSessionRecord> queryOne(String column, String hash) {
        List<AuthSessionRecord> rows = jdbc.query(
                "SELECT * FROM auth_sessions WHERE " + column + " = ? AND revoked_at IS NULL LIMIT 1",
                MAPPER, hash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static final RowMapper<AuthSessionRecord> MAPPER = (rs, n) -> new AuthSessionRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("identity_id")),
            UUID.fromString(rs.getString("active_workspace_id")),
            rs.getString("access_token_hash"),
            rs.getString("refresh_token_hash"),
            rs.getString("device_label"),
            rs.getString("user_agent"),
            rs.getString("ip_address"),
            toLdt(rs.getTimestamp("created_at")),
            toLdt(rs.getTimestamp("access_expires_at")),
            toLdt(rs.getTimestamp("refresh_expires_at")),
            toLdt(rs.getTimestamp("revoked_at")),
            toLdt(rs.getTimestamp("last_used_at"))
    );

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
