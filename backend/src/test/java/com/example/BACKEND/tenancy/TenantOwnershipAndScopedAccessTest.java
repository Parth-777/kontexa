package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.auth.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantOwnershipAndScopedAccessTest {

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void matchesPrefersUuidTenantId() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext ctx = new TenantContext(
                tenantA, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");

        assertTrue(TenantOwnership.matches(tenantA, "other-slug", ctx));
        assertFalse(TenantOwnership.matches(tenantB, "acme", ctx));
    }

    @Test
    void matchesFallsBackToLegacySlugWhenTenantIdNull() {
        TenantContext ctx = new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");

        assertTrue(TenantOwnership.matches(null, "acme", ctx));
        assertFalse(TenantOwnership.matches(null, "other", ctx));
    }

    @Test
    void requireMatchThrowsOnCrossTenant() {
        TenantContext ctx = new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");

        assertThrows(ForbiddenException.class,
                () -> TenantOwnership.requireMatch(UUID.randomUUID(), "acme", ctx, "Catalogue 1"));
    }

    @Test
    void tenantIdBinderSetsFromContext() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(new TenantContext(
                tenantId, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        final UUID[] captured = new UUID[1];
        TenantIdBinder.bind(id -> captured[0] = id);
        assertTrue(tenantId.equals(captured[0]));
    }

    @Test
    void scopedAccessPrefersTenantIdList() {
        TenantScopedAccess access = new TenantScopedAccess();
        UUID tenantId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(
                tenantId, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");

        var result = access.list(
                ctx,
                id -> java.util.List.of("by-uuid"),
                slug -> java.util.List.of("by-slug"));
        assertTrue(result.equals(java.util.List.of("by-uuid")));
    }

    @Test
    void scopedAccessFallsBackToLegacyWhenUuidEmpty() {
        TenantScopedAccess access = new TenantScopedAccess();
        TenantContext ctx = new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD");

        var result = access.list(
                ctx,
                id -> java.util.List.of(),
                slug -> java.util.List.of("by-slug"));
        assertTrue(result.equals(java.util.List.of("by-slug")));
    }
}
