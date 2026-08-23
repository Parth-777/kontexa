package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import com.example.BACKEND.identity.auth.AuthContext;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.identity.auth.AuthorizationService;
import com.example.BACKEND.identity.connector.WorkspaceConnectorService;
import com.example.BACKEND.tenancy.TenantContext;
import com.example.BACKEND.tenancy.TenantRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantPostgresAuthControllerTest {

    @Mock TenantAuthService tenantAuthService;
    @Mock BigQueryConnectorService bigQueryConnectorService;
    @Mock SnowflakeConnectorService snowflakeConnectorService;
    @Mock PostgresConnectorService postgresConnectorService;
    @Mock TenantContextResolver tenantContextResolver;
    @Mock TenantCloudConnectionService cloudConnectionService;
    @Mock WorkspaceConnectorService workspaceConnectorService;

    private TenantAuthController controller;
    private TenantContext ctx;

    @BeforeEach
    void setUp() {
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "acme", "user@acme.test", "User", Role.ADMIN, "test"));
        controller = new TenantAuthController(
                tenantAuthService, bigQueryConnectorService, snowflakeConnectorService,
                postgresConnectorService, new ObjectMapper(), tenantContextResolver,
                cloudConnectionService, workspaceConnectorService, new AuthorizationService());
        ctx = new TenantContext(
                UUID.randomUUID(), "acme", UUID.randomUUID(),
                "user@acme.test", "User", TenantRole.ADMIN, UUID.randomUUID(), "PASSWORD");
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    @Test
    void getPostgresConfigOmitsPassword() {
        when(tenantContextResolver.resolveContext(null, "acme")).thenReturn(ctx);
        when(cloudConnectionService.getRawConfig(ctx.tenantId(), "POSTGRESQL"))
                .thenReturn(Optional.of("""
                        {"provider":"postgresql","host":"db.example.com","port":5432,"database":"analytics",
                        "schema":"public","username":"warehouse_user","password":"SUPER_SECRET","ssl":true}
                        """));

        ResponseEntity<?> response = controller.getPostgresConfig("acme");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("connected"));
        assertEquals("postgresql", body.get("provider"));
        assertEquals("db.example.com", body.get("host"));
        assertEquals(5432, body.get("port"));
        assertEquals("analytics", body.get("database"));
        assertEquals("public", body.get("schema"));
        assertEquals(true, body.get("ssl"));
        assertEquals(true, body.get("credentialsStored"));
        assertFalse(body.containsKey("password"));
        assertFalse(body.containsKey("username"));
        assertFalse(String.valueOf(body).contains("SUPER_SECRET"));
    }

    @Test
    void getPostgresConfigRejectsLocalPostgresProviderValue() {
        when(tenantContextResolver.resolveContext(null, "acme")).thenReturn(ctx);
        when(cloudConnectionService.getRawConfig(ctx.tenantId(), "POSTGRESQL"))
                .thenReturn(Optional.of("{\"provider\":\"postgres\",\"host\":\"localhost\",\"password\":\"x\"}"));

        ResponseEntity<?> response = controller.getPostgresConfig("acme");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("connected"));
        assertEquals("postgresql", body.get("provider"));
        assertFalse(body.containsKey("password"));
        assertTrue(!String.valueOf(body).contains("localhost") || Boolean.FALSE.equals(body.get("connected")));
    }
}
