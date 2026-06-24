package com.example.BACKEND.identity;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.*;
import com.example.BACKEND.identity.auth.IdentityAuthResolver.ResolvedLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC data access for the workspace identity system.
 *
 * All operations use ON CONFLICT guards for idempotent migration safety —
 * each method can be called multiple times without creating duplicate records.
 */
@Repository
public class IdentityRepository {

    private static final Logger log = LoggerFactory.getLogger(IdentityRepository.class);

    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Workspace ─────────────────────────────────────────────────────────────

    /**
     * Creates a workspace if the slug does not already exist.
     * Returns the ID of the workspace (new or existing).
     */
    public UUID createWorkspaceIfAbsent(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workspaces (id, name, slug, status, identity_provider_type)
                VALUES (?, ?, ?, 'ACTIVE', 'LOCAL_PASSWORD')
                ON CONFLICT (slug) DO NOTHING
                """, id, name, slug);
        return findWorkspaceIdBySlug(slug).orElse(id);
    }

    public Optional<UUID> findWorkspaceIdBySlug(String slug) {
        List<UUID> rows = jdbc.query(
                "SELECT id FROM workspaces WHERE slug = ? LIMIT 1",
                (rs, n) -> UUID.fromString(rs.getString("id")),
                slug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Workspace> findWorkspaceBySlug(String slug) {
        List<Workspace> rows = jdbc.query(
                """
                SELECT id, name, slug, status, created_at, identity_provider_type, sso_config
                FROM workspaces WHERE slug = ? LIMIT 1
                """,
                WORKSPACE_MAPPER, slug);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Workspace> findWorkspaceById(UUID id) {
        List<Workspace> rows = jdbc.query(
                """
                SELECT id, name, slug, status, created_at, identity_provider_type, sso_config
                FROM workspaces WHERE id = ? LIMIT 1
                """,
                WORKSPACE_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public int countWorkspaces() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workspaces", Integer.class);
        return n == null ? 0 : n;
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Creates an identity for a workspace if (workspace_id, email) does not already exist.
     * Returns the ID of the identity (new or existing).
     */
    public UUID createOrRefreshInvitedIdentity(UUID workspaceId, String email) {
        String normalized = email.toLowerCase().trim();
        String defaultName = normalized.contains("@")
                ? normalized.substring(0, normalized.indexOf('@'))
                : normalized;

        Optional<Identity> existing = findIdentityByWorkspaceAndEmail(workspaceId, normalized);
        if (existing.isPresent()) {
            jdbc.update("""
                    UPDATE identities
                    SET status = 'INVITED', password_hash = NULL
                    WHERE id = ?
                    """, existing.get().id());
            return existing.get().id();
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identities
                    (id, workspace_id, email, display_name, auth_provider, password_hash, status)
                VALUES (?, ?, ?, ?, 'LOCAL_PASSWORD', NULL, 'INVITED')
                """,
                id, workspaceId, normalized, defaultName);
        return id;
    }

    public void activateIdentity(UUID identityId, String displayName, String passwordHash) {
        jdbc.update("""
                UPDATE identities
                SET display_name = ?, password_hash = ?, status = 'ACTIVE'
                WHERE id = ?
                """, displayName, passwordHash, identityId);
    }

    public void updateIdentityStatus(UUID identityId, String status) {
        jdbc.update("UPDATE identities SET status = ? WHERE id = ?", status, identityId);
    }

    public boolean hasActiveMembership(UUID workspaceId, UUID identityId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM workspace_memberships
                WHERE workspace_id = ? AND identity_id = ?
                """, Integer.class, workspaceId, identityId);
        return n != null && n > 0;
    }

    public UUID createIdentityIfAbsent(UUID workspaceId, String email, String displayName,
                                        String passwordHash, String migratedFrom, String legacyUserId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO identities
                    (id, workspace_id, email, display_name, auth_provider, password_hash,
                     status, migrated_from, legacy_user_id)
                VALUES (?, ?, ?, ?, 'LOCAL_PASSWORD', ?, 'ACTIVE', ?, ?)
                ON CONFLICT (workspace_id, email) DO NOTHING
                """,
                id, workspaceId, email.toLowerCase().trim(),
                displayName, passwordHash, migratedFrom, legacyUserId);
        return findIdentityByWorkspaceAndEmail(workspaceId, email)
                .map(Identity::id)
                .orElse(id);
    }

    public Optional<Identity> findIdentityByWorkspaceAndEmail(UUID workspaceId, String email) {
        List<Identity> rows = jdbc.query(
                """
                SELECT id, workspace_id, email, display_name, auth_provider, password_hash,
                       status, last_login_at, created_at, migrated_from, legacy_user_id
                FROM identities
                WHERE workspace_id = ? AND LOWER(email) = LOWER(?)
                LIMIT 1
                """,
                IDENTITY_MAPPER, workspaceId, email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Looks up an identity by email across all workspaces.
     * Used during dual-read fallback when workspaceSlug is not provided.
     */
    public Optional<Identity> findIdentityByEmailAcrossWorkspaces(String email) {
        List<Identity> rows = jdbc.query(
                """
                SELECT id, workspace_id, email, display_name, auth_provider, password_hash,
                       status, last_login_at, created_at, migrated_from, legacy_user_id
                FROM identities WHERE LOWER(email) = LOWER(?) LIMIT 1
                """,
                IDENTITY_MAPPER, email);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Identity> findIdentityById(UUID id) {
        List<Identity> rows = jdbc.query(
                """
                SELECT id, workspace_id, email, display_name, auth_provider, password_hash,
                       status, last_login_at, created_at, migrated_from, legacy_user_id
                FROM identities WHERE id = ? LIMIT 1
                """,
                IDENTITY_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** All workspaces an email can access — supports workspace switcher. */
    public List<Map<String, Object>> listWorkspacesForEmail(String email) {
        return jdbc.queryForList("""
                SELECT DISTINCT w.id AS workspace_id, w.slug, w.name, m.role, i.id AS identity_id
                FROM identities i
                JOIN workspace_memberships m ON m.identity_id = i.id
                JOIN workspaces w ON w.id = m.workspace_id
                WHERE LOWER(i.email) = LOWER(?)
                ORDER BY w.name
                """, email);
    }

    public Optional<ResolvedLogin> findMembershipLogin(
            UUID workspaceId, String email) {
        var rows = jdbc.queryForList("""
                SELECT i.id, i.workspace_id, i.email, i.display_name, i.auth_provider, i.password_hash,
                       i.status, i.last_login_at, i.created_at, i.migrated_from, i.legacy_user_id,
                       m.role, w.slug
                FROM identities i
                JOIN workspace_memberships m ON m.identity_id = i.id AND m.workspace_id = ?
                JOIN workspaces w ON w.id = m.workspace_id
                WHERE LOWER(i.email) = LOWER(?)
                LIMIT 1
                """, workspaceId, email);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(mapResolvedLogin(rows.getFirst()));
    }

    /** Resolve login by migrated legacy user_id (tenant id for admins, user id for app users). */
    public Optional<ResolvedLogin> findLoginByLegacyUserId(String legacyUserId) {
        var rows = jdbc.queryForList("""
                SELECT i.id, i.workspace_id, i.email, i.display_name, i.auth_provider, i.password_hash,
                       i.status, i.last_login_at, i.created_at, i.migrated_from, i.legacy_user_id,
                       m.role, w.slug, w.id AS ws_id
                FROM identities i
                JOIN workspace_memberships m ON m.identity_id = i.id
                JOIN workspaces w ON w.id = m.workspace_id
                WHERE LOWER(i.legacy_user_id) = LOWER(?)
                   OR LOWER(w.slug) = LOWER(?)
                ORDER BY CASE m.role WHEN 'OWNER' THEN 1 WHEN 'ADMIN' THEN 2 ELSE 3 END
                LIMIT 1
                """, legacyUserId, legacyUserId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(mapResolvedLogin(rows.getFirst()));
    }

    public Optional<ResolvedLogin> findFirstMembershipLogin(
            String email) {
        var rows = jdbc.queryForList("""
                SELECT i.id, i.workspace_id, i.email, i.display_name, i.auth_provider, i.password_hash,
                       i.status, i.last_login_at, i.created_at, i.migrated_from, i.legacy_user_id,
                       m.role, w.slug, w.id AS ws_id
                FROM identities i
                JOIN workspace_memberships m ON m.identity_id = i.id
                JOIN workspaces w ON w.id = m.workspace_id
                WHERE LOWER(i.email) = LOWER(?)
                   OR LOWER(i.legacy_user_id) = LOWER(?)
                ORDER BY CASE m.role WHEN 'OWNER' THEN 1 WHEN 'ADMIN' THEN 2 ELSE 3 END
                LIMIT 1
                """, email, email);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(mapResolvedLogin(rows.getFirst()));
    }

    private ResolvedLogin mapResolvedLogin(Map<String, Object> row) {
        Identity identity = new Identity(
                UUID.fromString(String.valueOf(row.get("id"))),
                row.get("workspace_id") != null ? UUID.fromString(String.valueOf(row.get("workspace_id"))) : null,
                String.valueOf(row.get("email")),
                row.get("display_name") != null ? String.valueOf(row.get("display_name")) : null,
                String.valueOf(row.get("auth_provider")),
                row.get("password_hash") != null ? String.valueOf(row.get("password_hash")) : null,
                String.valueOf(row.get("status")),
                null, null,
                row.get("migrated_from") != null ? String.valueOf(row.get("migrated_from")) : null,
                row.get("legacy_user_id") != null ? String.valueOf(row.get("legacy_user_id")) : null
        );
        UUID wsId = row.get("ws_id") != null
                ? UUID.fromString(String.valueOf(row.get("ws_id")))
                : UUID.fromString(String.valueOf(row.get("workspace_id")));
        String slug = String.valueOf(row.get("slug"));
        String role = String.valueOf(row.get("role"));
        return new ResolvedLogin(identity, wsId, slug, role);
    }

    public void updateLastLogin(UUID identityId) {
        jdbc.update("UPDATE identities SET last_login_at = NOW() WHERE id = ?", identityId);
    }

    public int countIdentities() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM identities", Integer.class);
        return n == null ? 0 : n;
    }

    // ── Workspace Membership ──────────────────────────────────────────────────

    /**
     * Creates or updates a membership.
     * ON CONFLICT updates the role so re-running migration is safe.
     */
    public UUID upsertMembership(UUID workspaceId, UUID identityId, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workspace_memberships (id, workspace_id, identity_id, role)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id, identity_id) DO UPDATE SET role = EXCLUDED.role
                """, id, workspaceId, identityId, role);
        return id;
    }

    public Optional<String> findRoleForIdentityInWorkspace(UUID workspaceId, UUID identityId) {
        List<String> rows = jdbc.query(
                "SELECT role FROM workspace_memberships WHERE workspace_id = ? AND identity_id = ? LIMIT 1",
                (rs, n) -> rs.getString("role"),
                workspaceId, identityId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listMembersForWorkspace(UUID workspaceId) {
        return jdbc.queryForList("""
                SELECT i.id, i.email, i.display_name, i.status, i.last_login_at,
                       i.auth_provider, i.created_at, m.role
                FROM identities i
                JOIN workspace_memberships m ON m.identity_id = i.id
                WHERE m.workspace_id = ?
                ORDER BY
                    CASE m.role
                        WHEN 'OWNER'   THEN 1
                        WHEN 'ADMIN'   THEN 2
                        WHEN 'ANALYST' THEN 3
                        ELSE 4
                    END,
                    i.email
                """, workspaceId);
    }

    // ── Workspace Connector ──────────────────────────────────────────────────

    /**
     * Creates or updates a connector for (workspace_id, warehouse_type).
     * Idempotent — safe to call multiple times.
     */
    public UUID upsertConnector(UUID workspaceId, String warehouseType, String credentialsJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO workspace_connectors
                    (id, workspace_id, warehouse_type, encrypted_credentials, status)
                VALUES (?, ?, ?, ?, 'CONNECTED')
                ON CONFLICT (workspace_id, warehouse_type) DO UPDATE
                    SET encrypted_credentials = EXCLUDED.encrypted_credentials,
                        status     = 'CONNECTED',
                        updated_at = NOW()
                """, id, workspaceId, warehouseType, credentialsJson);
        List<UUID> existing = jdbc.query(
                "SELECT id FROM workspace_connectors WHERE workspace_id = ? AND warehouse_type = ? LIMIT 1",
                (rs, n) -> UUID.fromString(rs.getString("id")),
                workspaceId, warehouseType);
        return existing.isEmpty() ? id : existing.getFirst();
    }

    public Optional<String> findConnectorCredentials(UUID workspaceId) {
        List<String> rows = jdbc.query(
                """
                SELECT encrypted_credentials FROM workspace_connectors
                WHERE workspace_id = ? AND status = 'CONNECTED'
                ORDER BY updated_at DESC LIMIT 1
                """,
                (rs, n) -> rs.getString("encrypted_credentials"),
                workspaceId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    // ── Migration Log ─────────────────────────────────────────────────────────

    public void logMigrationEntry(String phase, String sourceTable, String sourceId,
                                   UUID workspaceId, UUID identityId, String status, String notes) {
        jdbc.update("""
                INSERT INTO identity_migration_log
                    (migration_phase, source_table, source_id, target_workspace_id,
                     target_identity_id, status, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, phase, sourceTable, sourceId, workspaceId, identityId, status, notes);
        log.debug("[migration-log] phase={} source={}/{} status={}", phase, sourceTable, sourceId, status);
    }

    public void updateMigrationLogStatus(String sourceTable, String sourceId, String status, String notes) {
        jdbc.update("""
                UPDATE identity_migration_log
                SET status = ?, notes = COALESCE(notes || ' | ', '') || ?
                WHERE source_table = ? AND source_id = ? AND status != 'ROLLED_BACK'
                """, status, notes, sourceTable, sourceId);
    }

    public Map<String, Object> getMigrationLogStats() {
        return jdbc.queryForMap("""
                SELECT
                    COALESCE(COUNT(*) FILTER (WHERE status = 'COMPLETED'),  0) AS completed,
                    COALESCE(COUNT(*) FILTER (WHERE status = 'FAILED'),     0) AS failed,
                    COALESCE(COUNT(*) FILTER (WHERE status = 'PENDING'),    0) AS pending,
                    COALESCE(COUNT(*) FILTER (WHERE status = 'ROLLED_BACK'),0) AS rolled_back,
                    COUNT(*) AS total
                FROM identity_migration_log
                """);
    }

    public void rollbackMigrationLog() {
        jdbc.update("UPDATE identity_migration_log SET status = 'ROLLED_BACK' WHERE status = 'COMPLETED'");
    }

    // ── Schema introspection ──────────────────────────────────────────────────

    public boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    public boolean identityTablesExist() {
        return tableExists("workspaces")
                && tableExists("identities")
                && tableExists("workspace_memberships");
    }

    public int countLegacyTenants(String legacyTableName) {
        if (!tableExists(legacyTableName)) return 0;
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public." + legacyTableName, Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[identity-repo] cannot count {}: {}", legacyTableName, e.getMessage());
            return 0;
        }
    }

    // ── Row mappers ───────────────────────────────────────────────────────────

    private static final RowMapper<Workspace> WORKSPACE_MAPPER = (rs, n) -> new Workspace(
            uuid(rs, "id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("status"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            rs.getString("identity_provider_type"),
            rs.getString("sso_config")
    );

    private static final RowMapper<Identity> IDENTITY_MAPPER = (rs, n) -> new Identity(
            uuid(rs, "id"),
            uuid(rs, "workspace_id"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getString("auth_provider"),
            rs.getString("password_hash"),
            rs.getString("status"),
            toLocalDateTime(rs.getTimestamp("last_login_at")),
            toLocalDateTime(rs.getTimestamp("created_at")),
            rs.getString("migrated_from"),
            rs.getString("legacy_user_id")
    );

    private static UUID uuid(ResultSet rs, String col) throws SQLException {
        String v = rs.getString(col);
        return v == null ? null : UUID.fromString(v);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
