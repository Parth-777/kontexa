package com.example.BACKEND.identity;

import com.example.BACKEND.identity.IdentityMigrationService.*;
import com.example.BACKEND.identity.auth.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Migration management REST API.
 * Requires authenticated OWNER/ADMIN. Not on AuthInterceptor public allowlist.
 */
@RestController
@RequestMapping("/api/admin/identity")
public class MigrationController {

    private static final Logger log = LoggerFactory.getLogger(MigrationController.class);

    private final IdentityMigrationService migrationService;
    private final WorkspaceAuthService authService;
    private final AuthorizationService authorization;

    public MigrationController(
            IdentityMigrationService migrationService,
            WorkspaceAuthService authService,
            AuthorizationService authorization
    ) {
        this.migrationService = migrationService;
        this.authService = authService;
        this.authorization = authorization;
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> runFullMigration() {
        authorization.requireAdmin();
        log.info("[migration-ctrl] Full migration triggered via API");
        try {
            MigrationRunResult result = migrationService.runFullMigration();
            return ResponseEntity.ok(Map.of(
                    "status",             "completed",
                    "tenantsMigrated",    result.tenantsMigrated(),
                    "usersMigrated",      result.usersMigrated(),
                    "connectorsMigrated", result.connectorsMigrated(),
                    "failedRecords",      result.failed(),
                    "message",            result.failed() > 0
                            ? "Migration completed with " + result.failed() + " failed record(s)"
                            : "Migration completed successfully"
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

    @PostMapping("/migrate/phase2a")
    public ResponseEntity<?> runPhase2a() {
        authorization.requireAdmin();
        try {
            int n = migrationService.migrateLegacyTenants();
            return ResponseEntity.ok(Map.of("status", "completed", "tenantsMigrated", n));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/migrate/phase2b")
    public ResponseEntity<?> runPhase2b() {
        authorization.requireAdmin();
        try {
            int n = migrationService.migrateLegacyUsers();
            return ResponseEntity.ok(Map.of("status", "completed", "usersMigrated", n));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/migrate/phase2c")
    public ResponseEntity<?> runPhase2c() {
        authorization.requireAdmin();
        try {
            int n = migrationService.migrateConnectors();
            return ResponseEntity.ok(Map.of("status", "completed", "connectorsMigrated", n));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/migrate/status")
    public ResponseEntity<?> status() {
        authorization.requireAdmin();
        return ResponseEntity.ok(authService.getMigrationStatus());
    }

    @GetMapping("/migrate/validate")
    public ResponseEntity<?> validate() {
        authorization.requireAdmin();
        return ResponseEntity.ok(migrationService.validateMigration());
    }

    @PostMapping("/migrate/rollback")
    public ResponseEntity<?> rollback(@RequestParam(defaultValue = "false") boolean confirm) {
        authorization.requireAdmin();
        log.warn("[migration-ctrl] Rollback requested confirm={}", confirm);
        try {
            // confirm=true → live delete; otherwise dry-run
            RollbackResult result = migrationService.rollbackMigration(!confirm);
            return ResponseEntity.ok(Map.of(
                    "dryRun", result.dryRun(),
                    "workspacesDeleted", result.workspacesDeleted(),
                    "identitiesDeleted", result.identitiesDeleted(),
                    "membershipsDeleted", result.membershipsDeleted(),
                    "connectorsDeleted", result.connectorsDeleted()
            ));
        } catch (Exception ex) {
            log.error("[migration-ctrl] Rollback failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
