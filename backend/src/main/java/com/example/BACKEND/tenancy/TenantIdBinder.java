package com.example.BACKEND.tenancy;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dual-writes UUID {@code tenant_id} onto new tenant-owned rows when a request
 * {@link TenantContext} is bound. No-op outside HTTP request scope.
 */
public final class TenantIdBinder {

    private TenantIdBinder() {}

    public static void bind(Consumer<UUID> tenantIdSetter) {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx != null && tenantIdSetter != null) {
            tenantIdSetter.accept(ctx.tenantId());
        }
    }

    public static void bind(Consumer<UUID> tenantIdSetter, UUID explicitTenantId) {
        if (explicitTenantId != null && tenantIdSetter != null) {
            tenantIdSetter.accept(explicitTenantId);
            return;
        }
        bind(tenantIdSetter);
    }
}
