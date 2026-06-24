package com.example.BACKEND.identity;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.*;
import com.example.BACKEND.identity.invite.InviteService;
import com.example.BACKEND.tenant.TenantAuthService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.user.UserAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3 + Phase 4: Unified workspace authentication service.
 *
 * Dual-read architecture:
 *   This service supports both the new identity system and the legacy tables
 *   concurrently during the migration transition period.
 *
 * Auth resolution order:
 *   1. New identity system (identities + workspace_memberships)
 *      — If identity tables exist and identity is found: IDENTITY_V2 path
 *   2. Legacy tenant fallback (user_credentials via TenantAuthService)
 *      — For backward compatibility with admin logins not yet migrated
 *   3. Legacy user fallback (app_users via UserAccessService)
 *      — For backward compatibility with user logins not yet migrated
 *
 * The authSource field in AuthResult tells callers which backend handled the auth.
 * Once Phase 2 migration is complete and validated, fallback paths can be removed.
 *
 * Single login contract:
 *   - workspaceSlug is OPTIONAL during transition
 *   - If not provided: identity is resolved by email across all workspaces
 *   - email field doubles as userId for backward compat (userId is treated as email)
 */
@Service
public class WorkspaceAuthService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAuthService.class);

    private final IdentityRepository identityRepo;
    private final InviteService inviteService;
    private final TenantAuthService legacyTenantAuth;
    private final UserAccessService legacyUserAuth;
    private final TenantCloudConnectionService cloudConnectionService;

    public WorkspaceAuthService(
            IdentityRepository identityRepo,
            InviteService inviteService,
            TenantAuthService legacyTenantAuth,
            UserAccessService legacyUserAuth,
            TenantCloudConnectionService cloudConnectionService
    ) {
        this.identityRepo         = identityRepo;
        this.inviteService        = inviteService;
        this.legacyTenantAuth     = legacyTenantAuth;
        this.legacyUserAuth       = legacyUserAuth;
        this.cloudConnectionService = cloudConnectionService;
    }

    // ── Primary auth entry point ──────────────────────────────────────────────

    /**
     * Authenticate with email + password + optional workspaceSlug.
     *
     * During Phase 3 (dual-read), this falls through to legacy auth if the
     * new identity system doesn't have the record yet.
     *
     * @param emailOrUserId  the user's email or legacy userId
     * @param password       the user's password
     * @param workspaceSlug  optional; if null, resolution falls back to any workspace or legacy system
     */
    public Optional<AuthResult> authenticate(String emailOrUserId, String password, String workspaceSlug) {
        if (emailOrUserId == null || emailOrUserId.isBlank()
                || password == null || password.isBlank()) {
            return Optional.empty();
        }

        String email = emailOrUserId.trim();

        // ── Path 1: New identity system ────────────────────────────────────────
        if (identityRepo.identityTablesExist()) {
            Optional<AuthResult> v2Result = authenticateViaIdentitySystem(email, password, workspaceSlug);
            if (v2Result.isPresent()) {
                log.info("[workspace-auth] Authenticated via IDENTITY_V2: email={} workspace={}",
                        email, v2Result.get().workspaceSlug());
                return v2Result;
            }
        }

        // ── Path 2: Legacy tenant fallback (user_credentials) ─────────────────
        Optional<AuthResult> legacyTenantResult = authenticateViaLegacyTenant(email, password);
        if (legacyTenantResult.isPresent()) {
            log.info("[workspace-auth] Authenticated via LEGACY_TENANT: userId={}", email);
            return legacyTenantResult;
        }

        // ── Path 3: Legacy user fallback (app_users) ───────────────────────────
        Optional<AuthResult> legacyUserResult = authenticateViaLegacyUser(email, password);
        if (legacyUserResult.isPresent()) {
            log.info("[workspace-auth] Authenticated via LEGACY_USER: userId={}", email);
            return legacyUserResult;
        }

        log.debug("[workspace-auth] Auth failed for email/userId={}", email);
        return Optional.empty();
    }

    // ── Identity V2 path ──────────────────────────────────────────────────────

    private Optional<AuthResult> authenticateViaIdentitySystem(
            String email, String password, String workspaceSlug) {

        Optional<Identity> identityOpt;

        if (workspaceSlug != null && !workspaceSlug.isBlank()) {
            // Workspace-scoped lookup
            Optional<UUID> workspaceIdOpt = identityRepo.findWorkspaceIdBySlug(workspaceSlug.trim());
            if (workspaceIdOpt.isEmpty()) return Optional.empty();
            identityOpt = identityRepo.findIdentityByWorkspaceAndEmail(workspaceIdOpt.get(), email);
        } else {
            // Cross-workspace lookup (transition period)
            identityOpt = identityRepo.findIdentityByEmailAcrossWorkspaces(email);
        }

        if (identityOpt.isEmpty()) return Optional.empty();

        Identity identity = identityOpt.get();

        // Status check
        if (!"ACTIVE".equals(identity.status())) {
            log.debug("[workspace-auth] Identity {} is not ACTIVE: {}", identity.email(), identity.status());
            return Optional.empty();
        }

        // Password check (plain-text comparison during transition; BCrypt-ready column name)
        String storedHash = identity.passwordHash();
        if (storedHash == null || !storedHash.equals(password)) {
            return Optional.empty();
        }

        // Resolve workspace details
        Optional<Workspace> workspace = identityRepo.findWorkspaceBySlug(
                workspaceSlug != null ? workspaceSlug.trim()
                        : resolveSlugForWorkspace(identity.workspaceId())
        );
        String resolvedSlug = workspace.map(Workspace::slug).orElse("");

        // Resolve role from membership
        String role = identityRepo.findRoleForIdentityInWorkspace(identity.workspaceId(), identity.id())
                .orElse(Role.VIEWER);

        // Resolve connector config for downstream use
        String connectorJson = identityRepo.findConnectorCredentials(identity.workspaceId())
                .orElse(null);

        // Update last login
        identityRepo.updateLastLogin(identity.id());

        return Optional.of(new AuthResult(
                identity.id(),
                identity.workspaceId(),
                resolvedSlug,
                identity.email(),
                identity.displayName() != null ? identity.displayName() : identity.email(),
                role,
                connectorJson,
                AuthSource.IDENTITY_V2
        ));
    }

    // ── Legacy tenant path ────────────────────────────────────────────────────

    private Optional<AuthResult> authenticateViaLegacyTenant(String userId, String password) {
        try {
            return legacyTenantAuth.authenticate(userId, password)
                    .map(auth -> {
                        String connectorJson = cloudConnectionService.getProvider(auth.tenantId()).isBlank()
                                ? null
                                : resolveConnectorJson(auth.tenantId());

                        return new AuthResult(
                                null,            // no UUID identity during legacy path
                                null,            // no UUID workspace during legacy path
                                auth.tenantId(), // slug = tenantId for backward compat
                                userId,          // email = userId
                                auth.userId(),   // displayName = userId
                                Role.OWNER,      // legacy tenant owners are always OWNER
                                connectorJson,
                                AuthSource.LEGACY_TENANT
                        );
                    });
        } catch (Exception e) {
            log.debug("[workspace-auth] Legacy tenant auth threw: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── Legacy user path ──────────────────────────────────────────────────────

    private Optional<AuthResult> authenticateViaLegacyUser(String userId, String password) {
        try {
            return legacyUserAuth.authenticate(userId, password)
                    .map(result -> {
                        String connectorJson = cloudConnectionService.getProvider(result.tenantId()).isBlank()
                                ? null
                                : resolveConnectorJson(result.tenantId());

                        return new AuthResult(
                                null,
                                null,
                                result.tenantId(),
                                userId,
                                result.userId(),
                                Role.fromLegacyPosition(result.position()),
                                connectorJson,
                                AuthSource.LEGACY_USER
                        );
                    });
        } catch (Exception e) {
            log.debug("[workspace-auth] Legacy user auth threw: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── Workspace info ────────────────────────────────────────────────────────

    public Optional<Workspace> getWorkspace(String slug) {
        return identityRepo.findWorkspaceBySlug(slug);
    }

    // ── Member management ─────────────────────────────────────────────────────

    public java.util.List<java.util.Map<String, Object>> listMembers(UUID workspaceId) {
        return inviteService.listTeamMembers(workspaceId);
    }

    public void updateMemberRole(UUID workspaceId, UUID identityId, String role) {
        identityRepo.upsertMembership(workspaceId, identityId, role);
    }

    public void updateMemberStatus(UUID workspaceId, UUID identityId, boolean active) {
        identityRepo.updateIdentityStatus(identityId, active ? "ACTIVE" : "SUSPENDED");
    }

    // ── Migration status ──────────────────────────────────────────────────────

    public MigrationStatus getMigrationStatus() {
        boolean tablesExist = identityRepo.identityTablesExist();
        int legacyTenants   = identityRepo.countLegacyTenants("user_credentials");
        int legacyUsers     = identityRepo.countLegacyTenants("app_users");
        int newWorkspaces   = tablesExist ? identityRepo.countWorkspaces()  : 0;
        int newIdentities   = tablesExist ? identityRepo.countIdentities()  : 0;

        java.util.Map<String, Object> stats = tablesExist
                ? identityRepo.getMigrationLogStats()
                : java.util.Map.of("completed", 0, "failed", 0, "pending", 0);

        int completed = toInt(stats.get("completed"));
        int failed    = toInt(stats.get("failed"));
        int pending   = toInt(stats.get("pending"));

        String overallStatus;
        if (!tablesExist) {
            overallStatus = "NOT_STARTED";
        } else if (newWorkspaces == 0 && newIdentities == 0) {
            overallStatus = "NOT_STARTED";
        } else if (failed > 0 && completed == 0) {
            overallStatus = "FAILED";
        } else if (pending > 0 || failed > 0) {
            overallStatus = "PARTIAL";
        } else if (newIdentities >= (legacyTenants + legacyUsers)) {
            overallStatus = "COMPLETED";
        } else {
            overallStatus = "IN_PROGRESS";
        }

        return new MigrationStatus(tablesExist, legacyTenants, legacyUsers,
                newWorkspaces, newIdentities, completed, failed, pending, overallStatus);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveSlugForWorkspace(UUID workspaceId) {
        if (workspaceId == null) return "";
        try {
            return identityRepo.findWorkspaceBySlug(workspaceId.toString())
                    .map(Workspace::slug).orElse(workspaceId.toString());
        } catch (Exception e) {
            return workspaceId.toString();
        }
    }

    private String resolveConnectorJson(String tenantId) {
        try {
            var bqCfg = cloudConnectionService.getBigQueryConfig(tenantId);
            if (bqCfg.isPresent()) {
                var cfg = bqCfg.get();
                return "{\"provider\":\"bigquery\",\"projectId\":\"" + cfg.projectId()
                        + "\",\"dataset\":\"" + cfg.dataset()
                        + "\",\"location\":\"" + cfg.location() + "\"}";
            }
            var sfCfg = cloudConnectionService.getSnowflakeConfig(tenantId);
            if (sfCfg.isPresent()) {
                var cfg = sfCfg.get();
                return "{\"provider\":\"snowflake\",\"account\":\"" + cfg.account()
                        + "\",\"warehouse\":\"" + cfg.warehouse()
                        + "\",\"database\":\"" + cfg.database() + "\"}";
            }
        } catch (Exception e) {
            log.debug("[workspace-auth] Could not resolve connector for {}: {}", tenantId, e.getMessage());
        }
        return null;
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { return 0; }
    }
}
