package com.example.BACKEND.identity.auth;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Authorization layer — separate from authentication.
 * Checks role permissions for workspace-scoped actions.
 */
@Service
public class AuthorizationService {

    private static final Set<String> ADMIN_ROLES = Set.of(Role.OWNER, Role.ADMIN);

    public void requireAuthenticated() {
        AuthContextHolder.require();
    }

    public void requireRole(String... allowedRoles) {
        AuthContext ctx = AuthContextHolder.require();
        for (String allowed : allowedRoles) {
            if (allowed.equalsIgnoreCase(ctx.role())) return;
        }
        throw new ForbiddenException("Insufficient permissions. Required: " + String.join(", ", allowedRoles));
    }

    public void requireAdmin() {
        AuthContext ctx = AuthContextHolder.require();
        if (!ADMIN_ROLES.contains(ctx.role())) {
            throw new ForbiddenException("Admin access required");
        }
    }

    public boolean isAdmin(AuthContext ctx) {
        return ctx != null && ADMIN_ROLES.contains(ctx.role());
    }

    public boolean canQuery(AuthContext ctx) {
        if (ctx == null) return false;
        return Set.of(Role.OWNER, Role.ADMIN, Role.ANALYST).contains(ctx.role());
    }
}
