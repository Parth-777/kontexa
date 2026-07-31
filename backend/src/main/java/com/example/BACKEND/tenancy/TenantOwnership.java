package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.auth.ForbiddenException;

import java.util.Objects;
import java.util.UUID;

/**
 * Ownership checks for tenant-owned rows that may carry both UUID {@code tenant_id}
 * and legacy string {@code client_id} during Phase B dual-read.
 */
public final class TenantOwnership {

    private TenantOwnership() {}

    /**
     * True when the row belongs to the authenticated tenant.
     * Prefer UUID match; fall back to legacy slug when {@code rowTenantId} is null.
     */
    public static boolean matches(
            UUID rowTenantId,
            String rowClientId,
            TenantContext ctx
    ) {
        Objects.requireNonNull(ctx, "ctx");
        if (rowTenantId != null) {
            return rowTenantId.equals(ctx.tenantId());
        }
        String legacy = ctx.legacyTenantKey();
        return rowClientId != null
                && legacy != null
                && !legacy.isBlank()
                && rowClientId.equalsIgnoreCase(legacy);
    }

    public static void requireMatch(
            UUID rowTenantId,
            String rowClientId,
            TenantContext ctx,
            String resourceLabel
    ) {
        if (!matches(rowTenantId, rowClientId, ctx)) {
            throw new ForbiddenException(
                    resourceLabel + " does not belong to authenticated tenant");
        }
    }
}
