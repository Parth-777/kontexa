package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void fromAuthContextBuildsImmutableTenantContext() {
        UUID sessionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthContext auth = new AuthContext(
                sessionId, userId, tenantId, "acme",
                "a@acme.com", "Ada", "ADMIN", "IDENTITY_V2");

        TenantContext ctx = TenantContext.fromAuthContext(auth);

        assertEquals(tenantId, ctx.tenantId());
        assertEquals(userId, ctx.userId());
        assertEquals("acme", ctx.tenantSlug());
        assertEquals(TenantRole.ADMIN, ctx.role());
        assertEquals(sessionId, ctx.sessionId());
        assertEquals("acme", ctx.legacyTenantKey());
    }

    @Test
    void fromAuthContextFailsClosedWhenTenantMissing() {
        AuthContext auth = new AuthContext(
                UUID.randomUUID(), UUID.randomUUID(), null, "acme",
                "a@acme.com", "Ada", "ADMIN", "IDENTITY_V2");
        assertThrows(MissingTenantContextException.class,
                () -> TenantContext.fromAuthContext(auth));
    }

    @Test
    void holderRequireFailsClosedWhenEmpty() {
        assertThrows(MissingTenantContextException.class, TenantContextHolder::require);
    }

    @Test
    void holderStoresAndClears() {
        TenantContext ctx = new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(),
                "a@acme.com", "Ada", TenantRole.OWNER,
                UUID.randomUUID(), "IDENTITY_V2");
        TenantContextHolder.set(ctx);
        assertEquals(ctx.tenantId(), TenantContextHolder.require().tenantId());
        TenantContextHolder.clear();
        assertNull(TenantContextHolder.get());
    }

    @Test
    void repositoryInvariantRequiresTenantId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> TenantRepositoryInvariant.requireTenantId(null));
        assertTrue(ex.getMessage().contains("tenantId"));
    }
}
