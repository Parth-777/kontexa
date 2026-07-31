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
 * Membership persistence over {@code workspace_memberships}.
 * Every query requires tenantId.
 */
@Repository
public class MembershipRepository {

    private final JdbcTemplate jdbc;

    public MembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Membership> findByTenantAndUser(UUID tenantId, UUID userId) {
        TenantRepositoryInvariant.requireTenantAndUser(tenantId, userId);
        List<Membership> rows = jdbc.query(
                """
                SELECT id, workspace_id, identity_id, role, created_at
                FROM workspace_memberships
                WHERE workspace_id = ? AND identity_id = ?
                LIMIT 1
                """,
                MEMBERSHIP_MAPPER, tenantId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Membership> listByTenant(UUID tenantId) {
        TenantRepositoryInvariant.requireTenantId(tenantId);
        return jdbc.query(
                """
                SELECT id, workspace_id, identity_id, role, created_at
                FROM workspace_memberships
                WHERE workspace_id = ?
                ORDER BY created_at ASC
                """,
                MEMBERSHIP_MAPPER, tenantId);
    }

    public Optional<TenantRole> findRole(UUID tenantId, UUID userId) {
        return findByTenantAndUser(tenantId, userId).map(Membership::role);
    }

    private static final RowMapper<Membership> MEMBERSHIP_MAPPER = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        return new Membership(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("workspace_id")),
                UUID.fromString(rs.getString("identity_id")),
                TenantRole.fromDb(rs.getString("role")),
                created != null ? created.toLocalDateTime() : null
        );
    };
}
