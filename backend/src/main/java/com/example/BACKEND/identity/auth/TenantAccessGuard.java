package com.example.BACKEND.identity.auth;

import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defense-in-depth tenant isolation guard.
 *
 * Authoritative tenant is the {@link TenantContext} resolved at authentication
 * (fallback: {@link AuthContext#workspaceSlug()} during transition).
 */
public final class TenantAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessGuard.class);

    private TenantAccessGuard() {}

    /**
     * Assert that {@code executionTenant} equals the authenticated tenant slug.
     *
     * @param executionTenant workspace slug the execution layer is about to use
     * @throws ForbiddenException on mismatch when a request-bound context exists
     */
    public static void assertTenantMatchesAuthContext(String executionTenant) {
        TenantContext tenantContext = TenantContextHolder.get();
        if (tenantContext != null) {
            String sessionTenant = tenantContext.tenantSlug();
            String requested = executionTenant == null ? null : executionTenant.trim();
            if (sessionTenant == null || sessionTenant.isBlank()
                    || requested == null
                    || !sessionTenant.equals(requested)) {
                log.error("[tenant-guard] SECURITY: blocked cross-tenant execution — "
                                + "executionTenant='{}' authenticatedTenant='{}'",
                        executionTenant, sessionTenant);
                throw new ForbiddenException(
                        "Tenant mismatch between request and authenticated workspace.");
            }
            return;
        }

        AuthContext ctx = AuthContextHolder.get();
        if (ctx == null || !ctx.hasWorkspace()) {
            return;
        }
        String sessionTenant = ctx.workspaceSlug();
        if (sessionTenant == null || sessionTenant.isBlank()) {
            return;
        }
        String requested = executionTenant == null ? null : executionTenant.trim();
        if (requested == null || !sessionTenant.equals(requested)) {
            log.error("[tenant-guard] SECURITY: blocked cross-tenant execution — "
                            + "executionTenant='{}' authenticatedWorkspace='{}'",
                    executionTenant, sessionTenant);
            throw new ForbiddenException(
                    "Tenant mismatch between request and authenticated workspace.");
        }
    }
}
