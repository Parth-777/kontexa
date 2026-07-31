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
 * Tenant persistence over {@code workspaces}.
 */
@Repository
public class TenantRepository {

    private final JdbcTemplate jdbc;

    public TenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findById(UUID tenantId) {
        TenantRepositoryInvariant.requireTenantId(tenantId);
        List<Tenant> rows = jdbc.query(
                """
                SELECT id, name, slug, status, created_at
                FROM workspaces WHERE id = ? LIMIT 1
                """,
                TENANT_MAPPER, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Tenant> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        List<Tenant> rows = jdbc.query(
                """
                SELECT id, name, slug, status, created_at
                FROM workspaces WHERE slug = ? LIMIT 1
                """,
                TENANT_MAPPER, slug.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean existsActive(UUID tenantId) {
        return findById(tenantId).filter(Tenant::isActive).isPresent();
    }

    private static final RowMapper<Tenant> TENANT_MAPPER = (rs, n) -> {
        Timestamp created = rs.getTimestamp("created_at");
        LocalDateTime createdAt = created != null ? created.toLocalDateTime() : null;
        return new Tenant(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("status"),
                createdAt
        );
    };
}
