package com.example.BACKEND.identity.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defense-in-depth tenant isolation guard.
 *
 * The authoritative tenant for a request is the workspace bound to the
 * authenticated session ({@link AuthContext#workspaceSlug()}). This guard is
 * invoked deep in the execution layer (SQL execution / warehouse) so that even
 * if a future caller bypasses the controller-level checks, a request cannot be
 * served against a workspace other than the one it authenticated to.
 *
 * Behaviour:
 *  - If there is NO request-bound {@link AuthContext} on the current thread
 *    (e.g. a background/non-tenant-scoped code path), there is nothing to
 *    compare against and the guard is a no-op. Tenant-scoped HTTP APIs are made
 *    fail-closed at the interceptor, so the decision path always has a context.
 *  - If a context IS present and the execution tenant does not match the
 *    authenticated workspace, execution is aborted with a 403.
 *
 * This does NOT change tenant resolution, analytics, planners, SQL generation,
 * catalogue logic, or reasoning — it only asserts the trust boundary.
 */
public final class TenantAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessGuard.class);

    private TenantAccessGuard() {}

    /**
     * Assert that {@code executionTenant} equals the authenticated workspace.
     *
     * @param executionTenant the tenant identifier the execution layer is about
     *                         to run against (the workspace slug)
     * @throws ForbiddenException if a request-bound session exists and its
     *                            workspace differs from {@code executionTenant}
     */
    public static void assertTenantMatchesAuthContext(String executionTenant) {
        AuthContext ctx = AuthContextHolder.get();
        if (ctx == null || !ctx.hasWorkspace()) {
            // No request-bound identity to enforce against (non-tenant-scoped path).
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
