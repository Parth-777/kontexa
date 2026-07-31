package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.auth.AuthContext;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable tenant + user context resolved once at authentication.
 * Downstream services must receive this (or resources loaded with its tenantId)
 * and must not re-resolve tenant ownership from client headers.
 */
public record TenantContext(
        UUID tenantId,
        String tenantSlug,
        UUID userId,
        String email,
        String displayName,
        TenantRole role,
        UUID sessionId,
        String authMethod
) {
    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(sessionId, "sessionId");
        tenantSlug = tenantSlug == null ? "" : tenantSlug;
        email = email == null ? "" : email;
        displayName = displayName == null ? email : displayName;
        authMethod = authMethod == null || authMethod.isBlank() ? "PASSWORD" : authMethod;
    }

    /**
     * Builds TenantContext from the session {@link AuthContext}.
     * Fails closed if workspace/user ids are missing.
     */
    public static TenantContext fromAuthContext(AuthContext auth) {
        if (auth == null) {
            throw new MissingTenantContextException("Authentication required");
        }
        if (auth.workspaceId() == null || auth.identityId() == null || auth.sessionId() == null) {
            throw new MissingTenantContextException("TenantContext cannot be resolved from session");
        }
        return new TenantContext(
                auth.workspaceId(),
                auth.workspaceSlug(),
                auth.identityId(),
                auth.email(),
                auth.displayName(),
                TenantRole.fromDb(auth.role()),
                auth.sessionId(),
                auth.authSource() != null ? auth.authSource() : "PASSWORD"
        );
    }

    /** Legacy string key used by existing decision/catalogue paths (workspace slug). */
    public String legacyTenantKey() {
        return tenantSlug;
    }
}
