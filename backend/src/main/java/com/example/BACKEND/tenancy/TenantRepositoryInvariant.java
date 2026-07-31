package com.example.BACKEND.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Invariants for tenant-owned repositories.
 * Every method that touches tenant-owned data must receive a non-null tenantId.
 */
public final class TenantRepositoryInvariant {

    private TenantRepositoryInvariant() {}

    public static UUID requireTenantId(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required for tenant-owned repository access");
        }
        return tenantId;
    }

    public static void requireTenantAndUser(UUID tenantId, UUID userId) {
        requireTenantId(tenantId);
        Objects.requireNonNull(userId, "userId is required");
    }
}
