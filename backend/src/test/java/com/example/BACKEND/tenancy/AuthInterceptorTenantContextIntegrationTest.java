package com.example.BACKEND.tenancy;

import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthInterceptor;
import com.example.BACKEND.identity.auth.ForbiddenException;
import com.example.BACKEND.identity.session.SessionService;
import com.example.BACKEND.tenant.TenantContextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTenantContextIntegrationTest {

    @Mock
    private SessionService sessions;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void decisionPathFailClosedWithoutBearer() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/decision/v1/run");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertFalse(ok);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertNull(TenantContextHolder.get());
    }

    @Test
    void cataloguePathFailClosedWithoutBearer() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalogue/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertFalse(ok);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void agentPathFailClosedWithoutBearer() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agent/insights");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertFalse(ok);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void decisionPathBindsTenantContextOnce() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/decision/v1/run");
        request.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AuthContext auth = new AuthContext(
                sessionId, userId, tenantId, "acme",
                "a@acme.com", "Ada", "ANALYST", "IDENTITY_V2");

        when(sessions.isEnabled()).thenReturn(true);
        when(sessions.resolveAccessToken("tok")).thenReturn(Optional.of(auth));

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertTrue(ok);
        TenantContext ctx = TenantContextHolder.get();
        assertNotNull(ctx);
        assertEquals(tenantId, ctx.tenantId());
        assertEquals(userId, ctx.userId());
        assertEquals("acme", ctx.tenantSlug());

        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(TenantContextHolder.get());
    }

    @Test
    void decisionPathFailClosedWhenSessionCannotResolveTenant() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/decision/v1/run");
        request.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(sessions.isEnabled()).thenReturn(true);
        when(sessions.resolveAccessToken("bad")).thenReturn(Optional.empty());

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertFalse(ok);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void loginRemainsPublic() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertTrue(ok);
    }

    @Test
    void identityMigratePathFailClosedWithoutBearer() throws Exception {
        AuthInterceptor interceptor = new AuthInterceptor(sessions, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/identity/migrate");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = interceptor.preHandle(request, response, new Object());
        assertFalse(ok);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertNull(TenantContextHolder.get());
    }

    @Test
    void resolverUsesSessionTenantAndRejectsMismatchedClientHint() {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.set(new TenantContext(
                tenantId, "acme", UUID.randomUUID(), "a@acme.com", "Ada",
                TenantRole.ANALYST, UUID.randomUUID(), "PASSWORD"));

        TenantContextResolver resolver = new TenantContextResolver();
        assertEquals("acme", resolver.resolveClientId("acme", Map.of("clientId", "acme")));
        assertThrows(ForbiddenException.class,
                () -> resolver.resolveClientId("other-tenant", Map.of()));
        assertThrows(ForbiddenException.class,
                () -> resolver.resolveClientId(null, Map.of("tenantId", "evil")));
    }
}
