package com.example.BACKEND.identity.auth;

import com.example.BACKEND.catalogue.CatalogueController;
import com.example.BACKEND.catalogue.agent.AgentDashboardController;
import com.example.BACKEND.catalogue.agent.AgentOrchestrator;
import com.example.BACKEND.catalogue.agent.ReportGenerationAgent;
import com.example.BACKEND.catalogue.charts.DynamicChartService;
import com.example.BACKEND.catalogue.decision.api.DecisionController;
import com.example.BACKEND.catalogue.decision.api.DecisionResponseMapper;
import com.example.BACKEND.catalogue.decision.runtime.DecisionRuntime;
import com.example.BACKEND.catalogue.query.CatalogueQueryController;
import com.example.BACKEND.catalogue.query.ChatOrchestratorService;
import com.example.BACKEND.catalogue.repository.AgentReportRepository;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticPlanningProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
import com.example.BACKEND.catalogue.service.BigQueryCatalogueService;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.example.BACKEND.catalogue.service.CatalogueEnrichmentService;
import com.example.BACKEND.catalogue.service.CatalogueStorageService;
import com.example.BACKEND.catalogue.service.DataSamplerService;
import com.example.BACKEND.catalogue.service.PostgresCatalogueService;
import com.example.BACKEND.catalogue.service.SchemaDiscoveryService;
import com.example.BACKEND.catalogue.service.SnowflakeCatalogueService;
import com.example.BACKEND.identity.WorkspaceIdentityDomain.Role;
import com.example.BACKEND.identity.WorkspaceMembersController;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.invite.InviteService;
import com.example.BACKEND.identity.WorkspaceAuthController;
import com.example.BACKEND.identity.password.PasswordResetService;
import com.example.BACKEND.tenancy.TenantRole;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.PostgresConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantAuthController;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantContextResolver;
import com.example.BACKEND.user.UserAccessController;
import com.example.BACKEND.user.UserAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC gates on privileged mutations — OWNER/ADMIN allowed; ANALYST/VIEWER denied.
 * Authorization runs before business logic (collaborators never invoked on deny).
 */
@ExtendWith(MockitoExtension.class)
class RbacMutationAuthorizationIntegrationTest {

    private final AuthorizationService authorization = new AuthorizationService();

    @Mock UserAccessService userAccessService;
    @Mock TenantCloudConnectionService cloudConnectionService;
    @Mock BigQueryConnectorService bigQueryConnectorService;
    @Mock SnowflakeConnectorService snowflakeConnectorService;
    @Mock PostgresConnectorService postgresConnectorService;
    @Mock TenantContextResolver tenantContextResolver;
    @Mock InviteService inviteService;
    @Mock PasswordResetService passwordResetService;
    @Mock IdentityAuthResolver identityAuthResolver;
    @Mock AuthenticationService authenticationService;
    @Mock com.example.BACKEND.identity.WorkspaceAuthService workspaceAuthService;
    @Mock AuditService auditService;
    @Mock SchemaDiscoveryService schemaDiscovery;
    @Mock DataSamplerService dataSampler;
    @Mock CatalogueEnrichmentService enrichmentService;
    @Mock CatalogueStorageService storageService;
    @Mock CatalogueApprovalService approvalService;
    @Mock BigQueryCatalogueService bigQueryCatalogueService;
    @Mock SnowflakeCatalogueService snowflakeCatalogueService;
    @Mock PostgresCatalogueService postgresCatalogueService;
    @Mock DecisionRuntime decisionRuntime;
    @Mock DecisionResponseMapper decisionResponseMapper;
    @Mock SemanticPlanningProperties plannerProperties;
    @Mock SemanticShadowResponseMapper shadowResponseMapper;
    @Mock ChatOrchestratorService chatOrchestrator;
    @Mock AgentOrchestrator agentOrchestrator;
    @Mock ReportGenerationAgent reportAgent;
    @Mock AgentReportRepository reportRepository;
    @Mock DynamicChartService dynamicChartService;
    @Mock com.example.BACKEND.identity.connector.WorkspaceConnectorService workspaceConnectorService;
    @Mock TenantAuthServiceAdapter unused;

    private UserAccessController userAccessController;
    private WorkspaceMembersController membersController;
    private WorkspaceAuthController authController;
    private TenantAuthController tenantAuthController;
    private CatalogueController catalogueController;
    private DecisionController decisionController;
    private CatalogueQueryController queryController;
    private AgentDashboardController agentController;

    @BeforeEach
    void setUp() {
        AuthContextHolder.clear();
        userAccessController = new UserAccessController(
                userAccessService, cloudConnectionService, bigQueryConnectorService,
                snowflakeConnectorService, postgresConnectorService, tenantContextResolver, authorization);
        membersController = new WorkspaceMembersController(
                workspaceAuthService, authorization, auditService);
        authController = new WorkspaceAuthController(
                authenticationService, authorization, inviteService, passwordResetService,
                identityAuthResolver, workspaceAuthService);
        tenantAuthController = new TenantAuthController(
                null, bigQueryConnectorService, snowflakeConnectorService, postgresConnectorService, new ObjectMapper(),
                tenantContextResolver, cloudConnectionService, workspaceConnectorService, authorization);
        catalogueController = new CatalogueController(
                schemaDiscovery, dataSampler, enrichmentService, storageService, approvalService,
                cloudConnectionService, bigQueryCatalogueService, snowflakeCatalogueService, postgresCatalogueService,
                tenantContextResolver, authorization);
        decisionController = new DecisionController(
                decisionRuntime, decisionResponseMapper, plannerProperties,
                shadowResponseMapper, authorization);
        queryController = new CatalogueQueryController(
                chatOrchestrator, tenantContextResolver, authorization);
        agentController = new AgentDashboardController(
                agentOrchestrator, tenantContextResolver, reportAgent,
                reportRepository, dynamicChartService, authorization);
    }

    @AfterEach
    void tearDown() {
        AuthContextHolder.clear();
    }

    // ── User management ───────────────────────────────────────────────────────

    @Nested
    class UserManagement {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN})
        void ownerAdmin_canCreateUser(String role) {
            setRole(role);
            when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
            ResponseEntity<?> res = userAccessController.createUser(Map.of(
                    "userId", "u1", "password", "secret", "position", "Viewer"));
            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(userAccessService).createUser(anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedCreateUser(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> userAccessController.createUser(Map.of("userId", "u1", "password", "x")));
            verify(userAccessService, never()).createUser(anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedRoleChange(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> userAccessController.updateRole("u1", Map.of("position", "Admin")));
            verify(userAccessService, never()).updateRole(anyString(), anyString(), anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedStatusAndDelete(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> userAccessController.updateStatus("u1", Map.of("active", false)));
            assertThrows(ForbiddenException.class,
                    () -> userAccessController.deleteUser("u1", null));
            verify(userAccessService, never()).updateStatus(anyString(), anyString(), any(Boolean.class));
            verify(userAccessService, never()).deleteUser(anyString(), anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedListUsers(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class, () -> userAccessController.listUsers(null));
            verify(userAccessService, never()).listUsers(anyString());
        }
    }

    // ── Workspace members / role changes ──────────────────────────────────────

    @Nested
    class RoleChanges {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN})
        void ownerAdmin_canUpdateMemberRole(String role) {
            setRole(role);
            UUID target = UUID.randomUUID();
            HttpServletRequest req = new MockHttpServletRequest();
            ResponseEntity<?> res = membersController.updateRole(target, Map.of("role", "ANALYST"), req);
            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(workspaceAuthService).updateMemberRole(any(), any(), anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedMemberRoleChange(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> membersController.updateRole(UUID.randomUUID(), Map.of("role", "ADMIN"),
                            new MockHttpServletRequest()));
            verify(workspaceAuthService, never()).updateMemberRole(any(), any(), anyString());
        }
    }

    // ── Invitations ───────────────────────────────────────────────────────────

    @Nested
    class Invitations {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN})
        void ownerAdmin_canCreateInvite(String role) throws Exception {
            setRole(role);
            when(inviteService.createInvite(any(), anyString(), any(), any(), anyString(), nullable(String.class)))
                    .thenReturn(Map.of("inviteId", UUID.randomUUID().toString()));
            ResponseEntity<?> res = authController.createInvite(
                    Map.of("email", "new@acme.test", "role", "ANALYST"),
                    new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(inviteService).createInvite(any(), anyString(), any(), any(), anyString(), nullable(String.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedInviteMutations(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> authController.createInvite(Map.of("email", "x@y.z"), new MockHttpServletRequest()));
            assertThrows(ForbiddenException.class,
                    () -> authController.resendInvite(UUID.randomUUID(), new MockHttpServletRequest()));
            assertThrows(ForbiddenException.class,
                    () -> authController.revokeInvite(UUID.randomUUID(), new MockHttpServletRequest()));
            verify(inviteService, never()).createInvite(any(), anyString(), any(), any(), anyString(), anyString());
        }
    }

    // ── Password administration (create user with password) ───────────────────

    @Nested
    class PasswordAdministration {
        @Test
        void owner_canAdministerPasswordViaCreateUser() {
            setRole(Role.OWNER);
            when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
            assertDoesNotThrow(() -> userAccessController.createUser(Map.of(
                    "userId", "u2", "password", "new-pass", "position", "Analyst")));
            verify(userAccessService).createUser(anyString(), anyString(), anyString(), anyString(), any(Boolean.class));
        }

        @Test
        void viewer_cannotAdministerPasswords() {
            setRole(Role.VIEWER);
            assertThrows(ForbiddenException.class,
                    () -> userAccessController.createUser(Map.of("userId", "u2", "password", "x")));
        }

        @Test
        void publicPasswordReset_unchanged_noRoleRequired() {
            AuthContextHolder.clear();
            when(passwordResetService.requestReset(anyString(), anyString(), nullable(String.class)))
                    .thenReturn(java.util.Optional.of(Map.of("message", "ok")));
            ResponseEntity<?> res = authController.requestPasswordReset(
                    Map.of("email", "a@b.c"), new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, res.getStatusCode());
        }
    }

    // ── Connector management ──────────────────────────────────────────────────

    @Nested
    class ConnectorManagement {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN})
        void ownerAdmin_canUpdateCloudLink(String role) {
            setRole(role);
            var ctx = sampleTenantContext();
            when(tenantContextResolver.resolveContext(any(), any(Map.class))).thenReturn(ctx);
            when(workspaceConnectorService.detectProvider(anyString())).thenReturn("BIGQUERY");
            ResponseEntity<?> res = tenantAuthController.updateCloudLink(
                    Map.of("cloudDbLink", "{\"provider\":\"bigquery\"}"),
                    new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(cloudConnectionService).saveRawConfig(any(), anyString(), anyString(), anyString(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedConnectorMutations(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class,
                    () -> tenantAuthController.updateCloudLink(
                            Map.of("cloudDbLink", "{}"), new MockHttpServletRequest()));
            assertThrows(ForbiddenException.class,
                    () -> tenantAuthController.connectBigQuery(Map.of(), new MockHttpServletRequest()));
            assertThrows(ForbiddenException.class,
                    () -> tenantAuthController.testBigQueryConnection(Map.of()));
            assertThrows(ForbiddenException.class,
                    () -> tenantAuthController.connectSnowflake(Map.of(), new MockHttpServletRequest()));
            assertThrows(ForbiddenException.class,
                    () -> tenantAuthController.getCloudLink(null));
            verify(cloudConnectionService, never()).saveRawConfig(any(), anyString(), anyString(), anyString(), any());
        }
    }

    // ── Catalogue approval ────────────────────────────────────────────────────

    @Nested
    class CatalogueApproval {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN})
        void ownerAdmin_canApprove(String role) {
            setRole(role);
            var ctx = sampleTenantContext();
            org.mockito.Mockito.doReturn(ctx).when(tenantContextResolver)
                    .resolveContext(nullable(String.class), nullable(String.class));
            var entity = org.mockito.Mockito.mock(com.example.BACKEND.catalogue.entity.ClientCatalogueEntity.class);
            when(entity.getId()).thenReturn(1L);
            when(entity.getStatus()).thenReturn("APPROVED");
            when(entity.getClientId()).thenReturn("acme");
            when(approvalService.approve(1L, "acme")).thenReturn(entity);
            ResponseEntity<?> res = catalogueController.approve(1L, null, null);
            assertEquals(HttpStatus.OK, res.getStatusCode());
            verify(approvalService).approve(1L, "acme");
        }

        @ParameterizedTest
        @ValueSource(strings = {Role.ANALYST, Role.VIEWER})
        void analystViewer_deniedCatalogueMutations(String role) {
            setRole(role);
            assertThrows(ForbiddenException.class, () -> catalogueController.approve(1L, null, null));
            assertThrows(ForbiddenException.class, () -> catalogueController.reject(1L, null, null));
            assertThrows(ForbiddenException.class, () -> catalogueController.discover(null, Map.of()));
            assertThrows(ForbiddenException.class, () -> catalogueController.build(null, Map.of()));
            assertThrows(ForbiddenException.class, () -> catalogueController.buildFull(null, Map.of()));
            assertThrows(ForbiddenException.class,
                    () -> catalogueController.save(null, null, new com.example.BACKEND.catalogue.model.CatalogueResult()));
            assertThrows(ForbiddenException.class,
                    () -> catalogueController.updateById(1L, null, null, null, null, Map.of()));
            verify(approvalService, never()).approve(any(), anyString());
            verify(approvalService, never()).reject(any(), anyString());
        }
    }

    // ── Analytical mutations (VIEWER denied; ANALYST allowed) ─────────────────

    @Nested
    class AnalyticalMutations {
        @ParameterizedTest
        @ValueSource(strings = {Role.OWNER, Role.ADMIN, Role.ANALYST})
        void queryRoles_canAsk(String role) {
            setRole(role);
            when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
            when(chatOrchestrator.classifyRoute(anyString(), anyString()))
                    .thenReturn(ChatOrchestratorService.RouteType.REASONING);
            when(chatOrchestrator.handle(anyString(), anyString(), any()))
                    .thenReturn(new ChatOrchestratorService.ChatResponse(
                            "text", "answer", null, java.util.List.of(), 0, java.util.List.of()));
            ResponseEntity<?> res = queryController.ask(null, Map.of("question", "how many?"));
            assertEquals(HttpStatus.OK, res.getStatusCode());
        }

        @Test
        void viewer_deniedQueryAsk() {
            setRole(Role.VIEWER);
            assertThrows(ForbiddenException.class,
                    () -> queryController.ask(null, Map.of("question", "how many?")));
            verify(chatOrchestrator, never()).handle(anyString(), anyString(), any());
        }

        @Test
        void viewer_deniedDecisionRun() {
            setRole(Role.VIEWER);
            assertThrows(ForbiddenException.class,
                    () -> decisionController.executeRun(Map.of("question", "why?"), null));
            verify(decisionRuntime, never()).execute(any());
        }

        @Test
        void viewer_deniedAgentMutations() {
            setRole(Role.VIEWER);
            assertThrows(ForbiddenException.class,
                    () -> agentController.runDashboard(null, Map.of()));
            assertThrows(ForbiddenException.class,
                    () -> agentController.generateChart(null, Map.of("request", "chart")));
            assertThrows(ForbiddenException.class,
                    () -> agentController.generateReport(null, Map.of("type", "WEEKLY")));
            assertThrows(ForbiddenException.class,
                    () -> agentController.updateInsightStatus(UUID.randomUUID(), null, Map.of("status", "COMPLETED")));
            assertThrows(ForbiddenException.class,
                    () -> agentController.clearPendingInsights(null, null));
            verify(agentOrchestrator, never()).analyse(anyString());
        }

        @Test
        void analyst_canRunAgentDashboard() {
            setRole(Role.ANALYST);
            when(tenantContextResolver.resolveClientId(any(), any(Map.class))).thenReturn("acme");
            when(agentOrchestrator.analyse("acme"))
                    .thenReturn(org.mockito.Mockito.mock(com.example.BACKEND.catalogue.agent.AgentDashboardResult.class));
            ResponseEntity<?> res = agentController.runDashboard(null, Map.of());
            assertEquals(HttpStatus.OK, res.getStatusCode());
        }
    }

    // ── HTTP 403 contract ─────────────────────────────────────────────────────

    @Test
    void forbiddenException_mapsToHttp403() {
        var handler = new com.example.BACKEND.identity.config.AuthWebConfig.AuthExceptionHandler();
        ResponseEntity<Map<String, String>> res = handler.forbidden(new ForbiddenException("Admin access required"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("Admin access required", res.getBody().get("error"));
    }

    private static void setRole(String role) {
        AuthContextHolder.set(new AuthContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acme",
                "user@acme.test",
                "User",
                role,
                "test"
        ));
    }

    private static com.example.BACKEND.tenancy.TenantContext sampleTenantContext() {
        return new com.example.BACKEND.tenancy.TenantContext(
                UUID.randomUUID(),
                "acme",
                UUID.randomUUID(),
                "user@acme.test",
                "User",
                TenantRole.ADMIN,
                UUID.randomUUID(),
                "PASSWORD"
        );
    }

    /** Placeholder so MockitoExtension has a clean unused mock slot if needed. */
    interface TenantAuthServiceAdapter {}
}
