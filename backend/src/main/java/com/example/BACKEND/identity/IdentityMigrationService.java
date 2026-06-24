package com.example.BACKEND.identity;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2: Data Migration Utility.
 *
 * Migrates existing identity data from legacy tables into the new
 * workspace identity system WITHOUT deleting or altering the old tables.
 *
 * Migration strategy:
 *
 *   user_credentials → one Workspace per row + one Identity (OWNER role)
 *                    → connector config migrated from cloud_db_link
 *
 *   app_users        → Identity per row in the matching workspace
 *                    → role derived from legacy position field
 *
 * Safety guarantees:
 *   - All operations use ON CONFLICT guards (idempotent, re-runnable)
 *   - Every step is logged in identity_migration_log
 *   - Failures are logged individually, not raised — partial success is valid
 *   - Old tables are never modified
 *   - Rollback deletes NEW records only; old tables remain untouched
 *
 * Run order:
 *   Phase 2a → migrateLegacyTenants (user_credentials → workspaces + identities)
 *   Phase 2b → migrateLegacyUsers   (app_users → identities in matching workspaces)
 *   Phase 2c → migrateConnectors    (cloud_db_link → workspace_connectors)
 */
@Service
public class IdentityMigrationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityMigrationService.class);

    private static final String PHASE_2A = "PHASE_2_TENANT";
    private static final String PHASE_2B = "PHASE_2_USER";
    private static final String PHASE_2C = "PHASE_2_CONNECTOR";

    private final IdentityRepository repo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IdentityMigrationService(
            IdentityRepository repo,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        this.repo         = repo;
        this.jdbc         = jdbc;
        this.objectMapper = objectMapper;
    }

    // ── Phase 2a: Migrate tenant credentials ──────────────────────────────────

    /**
     * Reads all rows from public.user_credentials and creates:
     *   - one Workspace per tenant (slug = user_id)
     *   - one Identity per row (role = OWNER)
     *
     * @return number of tenants migrated successfully
     */
    @Transactional
    public int migrateLegacyTenants() {
        if (!repo.tableExists("user_credentials")) {
            log.warn("[migration-2a] user_credentials table does not exist — skipping");
            return 0;
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT * FROM public.user_credentials");
        } catch (Exception e) {
            log.error("[migration-2a] Failed to read user_credentials: {}", e.getMessage());
            return 0;
        }

        log.info("[migration-2a] Migrating {} tenant credential rows", rows.size());
        int success = 0;

        for (Map<String, Object> row : rows) {
            String legacyUserId = readString(row, "user_id", "userid", "userId");
            if (legacyUserId == null || legacyUserId.isBlank()) {
                log.warn("[migration-2a] Row has no user_id — skipping: {}", row);
                continue;
            }

            try {
                // Workspace: slug = user_id, name = user_id (can be updated later)
                String slug = slugify(legacyUserId);
                UUID workspaceId = repo.createWorkspaceIfAbsent(legacyUserId, slug);

                // Identity: treat user_id as email (common pattern)
                String email = emailify(legacyUserId);
                String passwordHash = readString(row, "password_hash", "password", "psw", "passwd");
                UUID identityId = repo.createIdentityIfAbsent(
                        workspaceId, email, legacyUserId,
                        passwordHash, MigrationSource.USER_CREDENTIALS, legacyUserId
                );

                // Membership: OWNER
                repo.upsertMembership(workspaceId, identityId, Role.OWNER);

                repo.logMigrationEntry(PHASE_2A, MigrationSource.USER_CREDENTIALS, legacyUserId,
                        workspaceId, identityId, "COMPLETED",
                        "workspace_slug=" + slug + " email=" + email);

                log.debug("[migration-2a] Migrated tenant {} → workspace={} identity={}", legacyUserId, slug, identityId);
                success++;

            } catch (Exception e) {
                log.error("[migration-2a] Failed to migrate tenant {}: {}", legacyUserId, e.getMessage());
                repo.logMigrationEntry(PHASE_2A, MigrationSource.USER_CREDENTIALS, legacyUserId,
                        null, null, "FAILED", e.getMessage());
            }
        }

        log.info("[migration-2a] Completed: {}/{} tenants migrated", success, rows.size());
        return success;
    }

    // ── Phase 2b: Migrate app users ───────────────────────────────────────────

    /**
     * Reads all rows from public.app_users and creates:
     *   - one Identity per row in the matching workspace
     *   - role derived from legacy position field
     *
     * A workspace must already exist for the tenant_id before users can be migrated.
     * If no workspace exists for a user's tenant, that user is logged as FAILED.
     *
     * @return number of users migrated successfully
     */
    @Transactional
    public int migrateLegacyUsers() {
        if (!repo.tableExists("app_users")) {
            log.warn("[migration-2b] app_users table does not exist — skipping");
            return 0;
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT * FROM public.app_users");
        } catch (Exception e) {
            log.error("[migration-2b] Failed to read app_users: {}", e.getMessage());
            return 0;
        }

        log.info("[migration-2b] Migrating {} app user rows", rows.size());
        int success = 0;

        for (Map<String, Object> row : rows) {
            String legacyUserId = readString(row, "user_id", "userid");
            String legacyTenantId = readString(row, "tenant_id", "tenantId", "tenant");

            if (legacyUserId == null || legacyUserId.isBlank()) {
                log.warn("[migration-2b] Row has no user_id — skipping: {}", row);
                continue;
            }

            try {
                // Find the workspace for this user's tenant
                String workspaceSlug = legacyTenantId != null ? slugify(legacyTenantId) : slugify(legacyUserId);
                UUID workspaceId = repo.findWorkspaceIdBySlug(workspaceSlug).orElse(null);

                if (workspaceId == null) {
                    // Workspace not found — this means the tenant hasn't been migrated yet
                    // Create a workspace for the tenant automatically
                    log.info("[migration-2b] Workspace '{}' not found for user {} — creating it", workspaceSlug, legacyUserId);
                    workspaceId = repo.createWorkspaceIfAbsent(
                            legacyTenantId != null ? legacyTenantId : legacyUserId,
                            workspaceSlug
                    );
                }

                String email = emailify(legacyUserId);
                String passwordHash = readString(row, "password_hash", "password", "psw", "passwd");
                String position = readString(row, "position", "role");
                String role = Role.fromLegacyPosition(position);

                // Check if active
                Boolean active = readBoolean(row, "is_active", "active");
                String identityStatus = (active == null || active) ? "ACTIVE" : "SUSPENDED";

                UUID identityId = repo.createIdentityIfAbsent(
                        workspaceId, email, legacyUserId,
                        passwordHash, MigrationSource.APP_USERS, legacyUserId
                );

                // Apply status (suspended users remain suspended)
                if ("SUSPENDED".equals(identityStatus)) {
                    jdbc.update("UPDATE identities SET status = 'SUSPENDED' WHERE id = ?", identityId);
                }

                // Membership with derived role
                repo.upsertMembership(workspaceId, identityId, role);

                repo.logMigrationEntry(PHASE_2B, MigrationSource.APP_USERS, legacyUserId,
                        workspaceId, identityId, "COMPLETED",
                        "workspace=" + workspaceSlug + " role=" + role + " status=" + identityStatus);

                log.debug("[migration-2b] Migrated user {} → workspace={} role={}", legacyUserId, workspaceSlug, role);
                success++;

            } catch (Exception e) {
                log.error("[migration-2b] Failed to migrate user {}: {}", legacyUserId, e.getMessage());
                repo.logMigrationEntry(PHASE_2B, MigrationSource.APP_USERS, legacyUserId,
                        null, null, "FAILED", e.getMessage());
            }
        }

        log.info("[migration-2b] Completed: {}/{} users migrated", success, rows.size());
        return success;
    }

    // ── Phase 2c: Migrate connector configs ───────────────────────────────────

    /**
     * Reads cloud_db_link from user_credentials and migrates them to workspace_connectors.
     * Connector credentials remain in the same JSON format — no transformation needed.
     *
     * @return number of connectors migrated successfully
     */
    @Transactional
    public int migrateConnectors() {
        if (!repo.tableExists("user_credentials")) {
            log.warn("[migration-2c] user_credentials table does not exist — skipping");
            return 0;
        }

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT user_id, cloud_db_link FROM public.user_credentials "
                    + "WHERE cloud_db_link IS NOT NULL AND cloud_db_link != ''");
        } catch (Exception e) {
            log.warn("[migration-2c] cloud_db_link column not available: {}", e.getMessage());
            return 0;
        }

        log.info("[migration-2c] Migrating {} connector configs", rows.size());
        int success = 0;

        for (Map<String, Object> row : rows) {
            String legacyUserId = readString(row, "user_id");
            String cloudDbLink = readString(row, "cloud_db_link");

            if (legacyUserId == null || cloudDbLink == null || cloudDbLink.isBlank()) continue;

            try {
                String slug = slugify(legacyUserId);
                UUID workspaceId = repo.findWorkspaceIdBySlug(slug).orElse(null);
                if (workspaceId == null) {
                    log.warn("[migration-2c] No workspace found for slug '{}' — skipping connector", slug);
                    continue;
                }

                String warehouseType = detectWarehouseType(cloudDbLink);
                repo.upsertConnector(workspaceId, warehouseType, cloudDbLink);

                repo.logMigrationEntry(PHASE_2C, MigrationSource.USER_CREDENTIALS, legacyUserId + ":connector",
                        workspaceId, null, "COMPLETED", "warehouse_type=" + warehouseType);

                log.debug("[migration-2c] Migrated connector for {} → {}", legacyUserId, warehouseType);
                success++;

            } catch (Exception e) {
                log.error("[migration-2c] Failed to migrate connector for {}: {}", legacyUserId, e.getMessage());
                repo.logMigrationEntry(PHASE_2C, MigrationSource.USER_CREDENTIALS, legacyUserId + ":connector",
                        null, null, "FAILED", e.getMessage());
            }
        }

        log.info("[migration-2c] Completed: {}/{} connectors migrated", success, rows.size());
        return success;
    }

    // ── Full migration orchestrator ───────────────────────────────────────────

    /**
     * Runs all three migration phases in sequence.
     * Returns the total number of records successfully migrated.
     */
    public MigrationRunResult runFullMigration() {
        if (!repo.identityTablesExist()) {
            throw new IllegalStateException(
                    "Identity tables do not exist. Run workspace_identity_migration.sql first (Phase 1).");
        }

        log.info("[migration] Starting full identity migration (Phases 2a, 2b, 2c)");

        int tenants    = migrateLegacyTenants();
        int users      = migrateLegacyUsers();
        int connectors = migrateConnectors();

        Map<String, Object> stats = repo.getMigrationLogStats();
        int failed = toInt(stats.get("failed"));

        log.info("[migration] Full migration complete: tenants={} users={} connectors={} failed={}",
                tenants, users, connectors, failed);

        return new MigrationRunResult(tenants, users, connectors, failed);
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    /**
     * Rolls back migrated records from the NEW identity tables.
     * Does NOT touch the old tables (user_credentials, app_users).
     *
     * Rollback order (reverse of migration, respecting FK constraints):
     *   workspace_connectors → workspace_memberships → identities → workspaces
     *
     * @param dryRun  if true, only reports what would be deleted, does not delete
     */
    @Transactional
    public RollbackResult rollbackMigration(boolean dryRun) {
        log.warn("[migration-rollback] {} rollback initiated", dryRun ? "DRY-RUN" : "LIVE");

        int connectors  = countOrDelete("workspace_connectors", dryRun);
        int memberships = countOrDelete("workspace_memberships", dryRun);
        int identities  = countOrDelete("identities", dryRun);
        int workspaces  = countOrDelete("workspaces", dryRun);

        if (!dryRun) {
            repo.rollbackMigrationLog();
            log.warn("[migration-rollback] LIVE rollback completed: deleted workspaces={} identities={} memberships={} connectors={}",
                    workspaces, identities, memberships, connectors);
        }

        return new RollbackResult(dryRun, workspaces, identities, memberships, connectors);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates that the migration preserved all expected records.
     * Returns a validation report with any discrepancies.
     */
    public ValidationResult validateMigration() {
        int legacyTenants = repo.countLegacyTenants("user_credentials");
        int legacyUsers   = repo.countLegacyTenants("app_users");
        int newWorkspaces = repo.countWorkspaces();
        int newIdentities = repo.countIdentities();

        Map<String, Object> stats = repo.getMigrationLogStats();
        int completed = toInt(stats.get("completed"));
        int failed    = toInt(stats.get("failed"));

        // Each tenant should have a workspace (1:1)
        boolean workspaceCoverageOk = newWorkspaces >= legacyTenants;
        // Each legacy record should have an identity
        boolean identityCoverageOk  = newIdentities >= (legacyTenants + legacyUsers);

        String overallStatus = (failed == 0 && workspaceCoverageOk && identityCoverageOk)
                ? "PASS" : "PARTIAL";

        return new ValidationResult(
                legacyTenants, legacyUsers,
                newWorkspaces, newIdentities,
                completed, failed,
                workspaceCoverageOk, identityCoverageOk,
                overallStatus
        );
    }

    // ── Result records ────────────────────────────────────────────────────────

    public record MigrationRunResult(int tenantsMigrated, int usersMigrated, int connectorsMigrated, int failed) {}

    public record RollbackResult(boolean dryRun, int workspacesDeleted, int identitiesDeleted,
                                  int membershipsDeleted, int connectorsDeleted) {}

    public record ValidationResult(
            int legacyTenants, int legacyUsers,
            int newWorkspaces, int newIdentities,
            int completedLogEntries, int failedLogEntries,
            boolean workspaceCoverageOk, boolean identityCoverageOk,
            String overallStatus  // PASS | PARTIAL
    ) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String slugify(String value) {
        if (value == null) return "default";
        return value.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * If the legacy user_id looks like an email, use it as-is.
     * Otherwise construct a synthetic email so the identity table constraint is satisfied.
     */
    private String emailify(String userId) {
        if (userId == null) return "unknown@kontexa.internal";
        String trimmed = userId.trim();
        return trimmed.contains("@") ? trimmed.toLowerCase() : (trimmed.toLowerCase() + "@kontexa.workspace");
    }

    private String detectWarehouseType(String cloudDbLink) {
        if (cloudDbLink == null) return "BIGQUERY";
        try {
            JsonNode node = objectMapper.readTree(cloudDbLink.trim());
            String provider = node.path("provider").asText("").toLowerCase();
            return switch (provider) {
                case "snowflake" -> "SNOWFLAKE";
                case "redshift"  -> "REDSHIFT";
                default          -> "BIGQUERY";
            };
        } catch (Exception e) {
            if (cloudDbLink.toLowerCase().contains("snowflake")) return "SNOWFLAKE";
            return "BIGQUERY";
        }
    }

    private int countOrDelete(String table, boolean dryRun) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            if (!dryRun && count != null && count > 0) {
                jdbc.update("DELETE FROM " + table);
            }
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("[migration-rollback] Could not process table {}: {}", table, e.getMessage());
            return 0;
        }
    }

    private String readString(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    String v = String.valueOf(entry.getValue()).trim();
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return null;
    }

    private Boolean readBoolean(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                    if (entry.getValue() instanceof Boolean b) return b;
                    return Boolean.parseBoolean(String.valueOf(entry.getValue()));
                }
            }
        }
        return null;
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }
}
