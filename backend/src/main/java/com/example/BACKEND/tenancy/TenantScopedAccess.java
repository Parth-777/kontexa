package com.example.BACKEND.tenancy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Dual-read helper: prefer UUID {@code tenant_id} results; fall back to legacy
 * {@code client_id}/slug queries until backfill is complete.
 */
@Component
public class TenantScopedAccess {

    public <T> List<T> list(
            TenantContext ctx,
            Function<UUID, List<T>> byTenantId,
            Function<String, List<T>> byLegacyKey
    ) {
        List<T> byId = byTenantId.apply(ctx.tenantId());
        if (byId != null && !byId.isEmpty()) {
            return byId;
        }
        String legacy = ctx.legacyTenantKey();
        if (legacy == null || legacy.isBlank()) {
            return byId != null ? byId : List.of();
        }
        List<T> byLegacy = byLegacyKey.apply(legacy);
        return byLegacy != null ? byLegacy : List.of();
    }

    public <T> Optional<T> find(
            TenantContext ctx,
            Function<UUID, Optional<T>> byTenantId,
            Function<String, Optional<T>> byLegacyKey
    ) {
        Optional<T> byId = byTenantId.apply(ctx.tenantId());
        if (byId != null && byId.isPresent()) {
            return byId;
        }
        String legacy = ctx.legacyTenantKey();
        if (legacy == null || legacy.isBlank()) {
            return Optional.empty();
        }
        Optional<T> byLegacy = byLegacyKey.apply(legacy);
        return byLegacy != null ? byLegacy : Optional.empty();
    }

    public int mutatePreferTenantId(
            TenantContext ctx,
            Supplier<Integer> byTenantId,
            Supplier<Integer> byLegacyKey
    ) {
        int updated = byTenantId.get();
        if (updated > 0) {
            return updated;
        }
        return byLegacyKey.get();
    }
}
