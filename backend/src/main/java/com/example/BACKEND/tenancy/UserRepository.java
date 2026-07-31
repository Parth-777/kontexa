package com.example.BACKEND.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * User persistence over {@code identities}.
 * Tenant-scoped lookups always require tenantId (via membership or legacy workspace_id).
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findById(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        List<User> rows = jdbc.query(
                """
                SELECT id, email, display_name, auth_provider, status, last_login_at, created_at
                FROM identities WHERE id = ? LIMIT 1
                """,
                USER_MAPPER, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Loads a user only if they have a membership in the given tenant
     * (or legacy identities.workspace_id match during transition).
     */
    public Optional<User> findByIdForTenant(UUID tenantId, UUID userId) {
        TenantRepositoryInvariant.requireTenantAndUser(tenantId, userId);
        List<User> rows = jdbc.query(
                """
                SELECT i.id, i.email, i.display_name, i.auth_provider, i.status, i.last_login_at, i.created_at
                FROM identities i
                WHERE i.id = ?
                  AND (
                    i.workspace_id = ?
                    OR EXISTS (
                        SELECT 1 FROM workspace_memberships m
                        WHERE m.identity_id = i.id AND m.workspace_id = ?
                    )
                  )
                LIMIT 1
                """,
                USER_MAPPER, userId, tenantId, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static final RowMapper<User> USER_MAPPER = (rs, n) -> {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        Timestamp created = rs.getTimestamp("created_at");
        return new User(
                UUID.fromString(rs.getString("id")),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("auth_provider"),
                rs.getString("status"),
                lastLogin != null ? lastLogin.toLocalDateTime() : null,
                created != null ? created.toLocalDateTime() : null
        );
    };
}
