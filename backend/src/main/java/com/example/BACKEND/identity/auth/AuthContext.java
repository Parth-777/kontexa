package com.example.BACKEND.identity.auth;

import java.util.UUID;

/**
 * Resolved authentication context for the current request.
 * Populated by {@link AuthInterceptor} after session validation.
 *
 * Authentication (who) is separate from authorization (what they can do).
 * Role checks belong in {@link AuthorizationService}.
 */
public record AuthContext(
        UUID sessionId,
        UUID identityId,
        UUID workspaceId,
        String workspaceSlug,
        String email,
        String displayName,
        String role,
        String authSource
) {
    public boolean hasWorkspace() {
        return workspaceId != null;
    }

    /** Canonical tenant id (UUID). Same as {@link #workspaceId()}. */
    public UUID tenantId() {
        return workspaceId;
    }

    /** Canonical user id (UUID). Same as {@link #identityId()}. */
    public UUID userId() {
        return identityId;
    }
}
