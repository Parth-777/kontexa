package com.example.BACKEND.identity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain records for the enterprise workspace identity system.
 *
 * Architecture:
 *   Workspace → the organisational security boundary (replaces tenantId as a string)
 *   Identity  → unified user record (replaces both user_credentials and app_users)
 *   WorkspaceMembership → role assignment (OWNER | ADMIN | ANALYST | VIEWER)
 *   WorkspaceConnector  → warehouse connector scoped to workspace
 *
 * Migration provenance:
 *   Identity.migratedFrom  → which legacy table the record came from
 *   Identity.legacyUserId  → the original user_id, preserved for traceability
 *
 * Auth provider pluggability:
 *   Identity.authProvider  → LOCAL_PASSWORD (current) | OKTA | AZURE_AD | SAML | GOOGLE_WORKSPACE
 *   Workspace.identityProviderType → future SSO routing key per workspace
 */
public final class WorkspaceIdentityDomain {

    private WorkspaceIdentityDomain() {}

    // ── Workspace ─────────────────────────────────────────────────────────────

    public record Workspace(
            UUID id,
            String name,
            String slug,
            String status,              // ACTIVE | SUSPENDED | PROVISIONING
            LocalDateTime createdAt,
            String identityProviderType, // LOCAL_PASSWORD | OKTA | AZURE_AD | SAML | GOOGLE_WORKSPACE
            String ssoConfig             // JSON; null until SSO configured
    ) {}

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Unified identity replacing both user_credentials (admins) and app_users (users).
     * Admins and users are NOT different entity types — they are identities with different roles.
     */
    public record Identity(
            UUID id,
            UUID workspaceId,
            String email,
            String displayName,
            String authProvider,   // LOCAL_PASSWORD | OKTA | AZURE_AD | SAML | GOOGLE_WORKSPACE
            String passwordHash,   // plain-text during transition; BCrypt-ready by column name
            String status,         // ACTIVE | SUSPENDED | INVITED | REVOKED
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            String migratedFrom,   // 'user_credentials' | 'app_users' | null (native)
            String legacyUserId    // original user_id; null for native identities
    ) {}

    // ── Workspace Membership ──────────────────────────────────────────────────

    public record WorkspaceMembership(
            UUID id,
            UUID workspaceId,
            UUID identityId,
            String role,           // OWNER | ADMIN | ANALYST | VIEWER
            LocalDateTime createdAt
    ) {}

    // ── Workspace Connector ──────────────────────────────────────────────────

    public record WorkspaceConnector(
            UUID id,
            UUID workspaceId,
            String warehouseType,          // BIGQUERY | SNOWFLAKE | REDSHIFT
            String encryptedCredentials,   // JSON blob (same format as cloud_db_link)
            String connectionMetadata,     // JSON: non-secret connection info
            String status,                 // CONNECTED | DISCONNECTED | ERROR
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    // ── Auth Result ───────────────────────────────────────────────────────────

    /**
     * Unified result returned by WorkspaceAuthService.
     * authSource identifies which backend authenticated the request during dual-read phase.
     */
    public record AuthResult(
            UUID identityId,
            UUID workspaceId,
            String workspaceSlug,
            String email,
            String displayName,
            String role,                   // OWNER | ADMIN | ANALYST | VIEWER
            String connectorConfigJson,    // connector JSON for downstream use; may be null
            String authSource              // IDENTITY_V2 | LEGACY_TENANT | LEGACY_USER
    ) {}

    // ── Migration Status ─────────────────────────────────────────────────────

    public record MigrationStatus(
            boolean identityTablesExist,
            int legacyTenantCount,
            int legacyUserCount,
            int migratedWorkspaces,
            int migratedIdentities,
            int completedLogEntries,
            int failedLogEntries,
            int pendingLogEntries,
            String overallStatus     // NOT_STARTED | IN_PROGRESS | COMPLETED | PARTIAL | FAILED
    ) {}

    // ── Membership Role constants ─────────────────────────────────────────────

    public static final class Role {
        public static final String OWNER   = "OWNER";
        public static final String ADMIN   = "ADMIN";
        public static final String ANALYST = "ANALYST";
        public static final String VIEWER  = "VIEWER";

        /** Maps legacy position strings from app_users to the new role model. */
        public static String fromLegacyPosition(String position) {
            if (position == null || position.isBlank()) return VIEWER;
            return switch (position.trim().toLowerCase()) {
                case "owner"   -> OWNER;
                case "admin"   -> ADMIN;
                case "analyst" -> ANALYST;
                default        -> VIEWER;
            };
        }

        private Role() {}
    }

    // ── Identity Provider constants ───────────────────────────────────────────

    public static final class AuthProvider {
        public static final String LOCAL_PASSWORD    = "LOCAL_PASSWORD";
        public static final String OKTA              = "OKTA";
        public static final String AZURE_AD          = "AZURE_AD";
        public static final String SAML              = "SAML";
        public static final String GOOGLE_WORKSPACE  = "GOOGLE_WORKSPACE";

        private AuthProvider() {}
    }

    // ── Migration provenance constants ────────────────────────────────────────

    public static final class MigrationSource {
        public static final String USER_CREDENTIALS = "user_credentials";
        public static final String APP_USERS        = "app_users";

        private MigrationSource() {}
    }

    // ── Auth source constants (for dual-read tracking) ────────────────────────

    public static final class AuthSource {
        public static final String IDENTITY_V2    = "IDENTITY_V2";
        public static final String LEGACY_TENANT  = "LEGACY_TENANT";
        public static final String LEGACY_USER    = "LEGACY_USER";

        private AuthSource() {}
    }
}
