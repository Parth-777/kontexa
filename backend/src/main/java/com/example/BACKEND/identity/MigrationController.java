package com.example.BACKEND.identity;

import com.example.BACKEND.identity.IdentityMigrationService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Migration management REST API.
 *
 * Provides endpoints to run, validate, and (if needed) roll back
 * the identity migration without touching the production database manually.
 *
 * Endpoints:
 *   POST /api/admin/identity/migrate         — run full migration (Phase 2a + 2b + 2c)
 *   POST /api/admin/identity/migrate/phase2a — migrate tenant credentials only
 *   POST /api/admin/identity/migrate/phase2b — migrate app users only
 *   POST /api/admin/identity/migrate/phase2c — migrate connector configs only
 *   GET  /api/admin/identity/migrate/status  — current migration status
 *   GET  /api/admin/identity/migrate/validate — validate migration completeness
 *   POST /api/admin/identity/migrate/rollback — roll back migrated records (dry-run by default)
 *
 * Security note:
 *   These endpoints should be protected by network policy or a shared secret header
 *   before deployment. In the current setup, restrict access to admin IP range.
 */
@RestController
@RequestMapping("/api/admin/identity")
public class MigrationController {

    private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

    private final IdentityMigrationService migrationService;
    private final WorkspaceAuthService authService;

    public MigrationController(
            IdentityMigrationService migrationService,
            WorkspaceAuthService authService
    ) {
        this.migrationService = migrationService;
        this.authService      = authService;
    }

    // ── Full migration ────────────────────────────────────────────────────────

    /**
     * Run the complete Phase 2 migration (2a + 2b + 2c) in sequence.
     *
     * Idempotent: safe to run multiple times.
     * Failed individual records are logged; the migration continues.
     */
    @PostMapping("/migrate")
    public ResponseEntity<?> runFullMigration() {
        log.info("[migration-ctrl] Full migration triggered via API");
        try {
            MigrationRunResult result = migrationService.runFullMigration();
            return ResponseEntity.ok(Map.of(
                    "status",             "completed",
                    "tenantsMigrated",    result.tenantsMigrated(),
                    "usersMigrated",      result.usersMigrated(),
                    "connectorsMigrated", result.connectorsMigrated(),
                    "failedRecords",      result.failed(),
                    "message",            buildMigrationMessage(result)
            ));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(Map.of("error", ex.getMessage(),
                                 "hint", "Run workspace_identity_migration.sql first to create Phase 1 tables"));
        } catch (Exception ex) {
            log.error("[migration-ctrl] Full migration failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Migration failed: " + ex.getMessage()));
        }
    }

    // ── Individual phase endpoints ────────────────────────────────────────────

    /** Phase 2a: Migrate user_credentials → workspaces + identities (OWNER role). */
    @PostMapping("/migrate/phase2a")
    public ResponseEntity<?> runPhase2a() {
        log.info("[migration-ctrl] Phase 2a (tenants) triggered via API");
        try {
            int migrated = migrationService.migrateLegacyTenants();
            return ResponseEntity.ok(Map.of(
                    "phase", "2a",
                    "description", "user_credentials → workspaces + identities (OWNER)",
                    "migrated", migrated
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /** Phase 2b: Migrate app_users → identities in matching workspaces. */
    @PostMapping("/migrate/phase2b")
    public ResponseEntity<?> runPhase2b() {
        log.info("[migration-ctrl] Phase 2b (users) triggered via API");
        try {
            int migrated = migrationService.migrateLegacyUsers();
            return ResponseEntity.ok(Map.of(
                    "phase", "2b",
                    "description", "app_users → identities with role mapping",
                    "migrated", migrated
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /** Phase 2c: Migrate cloud_db_link → workspace_connectors. */
    @PostMapping("/migrate/phase2c")
    public ResponseEntity<?> runPhase2c() {
        log.info("[migration-ctrl] Phase 2c (connectors) triggered via API");
        try {
            int migrated = migrationService.migrateConnectors();
            return ResponseEntity.ok(Map.of(
                    "phase", "2c",
                    "description", "user_credentials.cloud_db_link → workspace_connectors",
                    "migrated", migrated
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/migrate/status")
    public ResponseEntity<?> getMigrationStatus() {
        WorkspaceIdentityDomain.MigrationStatus status = authService.getMigrationStatus();
        java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("identityTablesExist",  status.identityTablesExist());
        response.put("legacyTenants",        status.legacyTenantCount());
        response.put("legacyUsers",          status.legacyUserCount());
        response.put("migratedWorkspaces",   status.migratedWorkspaces());
        response.put("migratedIdentities",   status.migratedIdentities());
        response.put("completedLogEntries",  status.completedLogEntries());
        response.put("failedLogEntries",     status.failedLogEntries());
        response.put("pendingLogEntries",    status.pendingLogEntries());
        response.put("overallStatus",        status.overallStatus());
        response.put("dualReadActive",       status.identityTablesExist() && !"COMPLETED".equals(status.overallStatus()));
        response.put("nextStep",             resolveNextStep(status));
        return ResponseEntity.ok(response);
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    /**
     * Validate that migration preserved all expected records.
     * Returns a detailed coverage report.
     */
    @GetMapping("/migrate/validate")
    public ResponseEntity<?> validateMigration() {
        try {
            ValidationResult result = migrationService.validateMigration();
            return ResponseEntity.ok(Map.of(
                    "overallStatus",         result.overallStatus(),
                    "legacyTenants",         result.legacyTenants(),
                    "legacyUsers",           result.legacyUsers(),
                    "newWorkspaces",         result.newWorkspaces(),
                    "newIdentities",         result.newIdentities(),
                    "completedLogEntries",   result.completedLogEntries(),
                    "failedLogEntries",      result.failedLogEntries(),
                    "workspaceCoverageOk",   result.workspaceCoverageOk(),
                    "identityCoverageOk",    result.identityCoverageOk(),
                    "recommendation",        resolveRecommendation(result)
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    /**
     * Roll back migrated records from new identity tables.
     * Old tables (user_credentials, app_users) are NEVER touched.
     *
     * By default, runs in DRY-RUN mode. Pass ?confirm=true to execute.
     *
     * WARNING: Only run after verifying old tables are intact.
     */
    @PostMapping("/migrate/rollback")
    public ResponseEntity<?> rollbackMigration(
            @RequestParam(defaultValue = "false") boolean confirm
    ) {
        boolean dryRun = !confirm;

        log.warn("[migration-ctrl] Rollback requested: dryRun={}", dryRun);

        if (!dryRun) {
            log.warn("[migration-ctrl] LIVE rollback executing — new identity tables will be cleared");
        }

        try {
            RollbackResult result = migrationService.rollbackMigration(dryRun);
            return ResponseEntity.ok(Map.of(
                    "dryRun",              result.dryRun(),
                    "workspacesAffected",  result.workspacesDeleted(),
                    "identitiesAffected",  result.identitiesDeleted(),
                    "membershipsAffected", result.membershipsDeleted(),
                    "connectorsAffected",  result.connectorsDeleted(),
                    "status",              dryRun ? "DRY_RUN_ONLY" : "ROLLED_BACK",
                    "message",             dryRun
                            ? "Dry run complete. Pass ?confirm=true to execute live rollback."
                            : "Rollback executed. New identity tables cleared. Legacy tables untouched."
            ));
        } catch (Exception ex) {
            log.error("[migration-ctrl] Rollback failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Rollback failed: " + ex.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildMigrationMessage(MigrationRunResult result) {
        if (result.failed() == 0) {
            return String.format("Migration successful: %d tenants, %d users, %d connectors migrated.",
                    result.tenantsMigrated(), result.usersMigrated(), result.connectorsMigrated());
        }
        return String.format("Migration partially successful: %d tenants, %d users, %d connectors migrated. "
                + "%d records failed — check identity_migration_log for details.",
                result.tenantsMigrated(), result.usersMigrated(), result.connectorsMigrated(), result.failed());
    }

    private String resolveNextStep(WorkspaceIdentityDomain.MigrationStatus status) {
        if (!status.identityTablesExist()) {
            return "Run workspace_identity_migration.sql in pgAdmin to create Phase 1 tables";
        }
        if (status.migratedWorkspaces() == 0) {
            return "POST /api/admin/identity/migrate to run Phase 2 data migration";
        }
        if (status.failedLogEntries() > 0) {
            return "Review identity_migration_log for failed records, then re-run /migrate";
        }
        if ("COMPLETED".equals(status.overallStatus())) {
            return "Migration complete. Dual-read is active. Validate with GET /migrate/validate";
        }
        return "Run GET /migrate/validate to check coverage";
    }

    private String resolveRecommendation(ValidationResult result) {
        if ("PASS".equals(result.overallStatus())) {
            return "Migration fully validated. All legacy records are represented in the new identity system.";
        }
        if (result.failedLogEntries() > 0) {
            return "Re-run POST /migrate to retry failed records, then re-validate.";
        }
        if (!result.workspaceCoverageOk()) {
            return "Some tenants were not migrated to workspaces. Run Phase 2a via POST /migrate/phase2a.";
        }
        if (!result.identityCoverageOk()) {
            return "Some users were not migrated to identities. Run Phase 2b via POST /migrate/phase2b.";
        }
        return "Partial coverage. Review identity_migration_log for details.";
    }
}
