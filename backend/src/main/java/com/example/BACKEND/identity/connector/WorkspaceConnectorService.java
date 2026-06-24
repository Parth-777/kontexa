package com.example.BACKEND.identity.connector;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.audit.AuditService;
import com.example.BACKEND.identity.auth.AuthContextHolder;
import com.example.BACKEND.tenant.TenantAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-scoped connector management.
 * Primary store: workspace_connectors.
 * Legacy fallback: user_credentials.cloud_db_link (read-only during transition).
 */
@Service
public class WorkspaceConnectorService {

    private final IdentityRepository identities;
    private final TenantAuthService legacyTenantAuth;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public WorkspaceConnectorService(
            IdentityRepository identities,
            TenantAuthService legacyTenantAuth,
            AuditService audit,
            ObjectMapper objectMapper
    ) {
        this.identities = identities;
        this.legacyTenantAuth = legacyTenantAuth;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public void saveConnector(UUID workspaceId, String warehouseType, String credentialsJson,
                              String ipAddress, String userAgent) {
        identities.upsertConnector(workspaceId, warehouseType, credentialsJson);

        UUID identityId = null;
        try {
            identityId = AuthContextHolder.get() != null ? AuthContextHolder.get().identityId() : null;
        } catch (Exception ignored) {}

        audit.log(workspaceId, identityId, AuditService.CONNECTOR_CHANGE,
                "warehouse=" + warehouseType, ipAddress, userAgent);
    }

    public Optional<String> getConnectorJson(UUID workspaceId) {
        Optional<String> fromWorkspace = identities.findConnectorCredentials(workspaceId);
        if (fromWorkspace.isPresent() && !fromWorkspace.get().isBlank()) {
            return fromWorkspace;
        }
        return Optional.empty();
    }

    public Optional<String> getConnectorJsonByWorkspaceSlug(String slug) {
        return identities.findWorkspaceIdBySlug(slug)
                .flatMap(this::getConnectorJson);
    }

    /**
     * Legacy read path — used when workspace UUID is unknown but tenant slug is available.
     */
    public Optional<String> getConnectorJsonLegacy(String tenantSlug) {
        Optional<String> ws = getConnectorJsonByWorkspaceSlug(tenantSlug);
        if (ws.isPresent()) return ws;

        try {
            return legacyTenantAuth.getCloudDbLink(tenantSlug);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String detectProvider(String credentialsJson) {
        if (credentialsJson == null || credentialsJson.isBlank()) return "";
        try {
            if (!credentialsJson.trim().startsWith("{")) {
                return credentialsJson.toLowerCase().contains("bigquery") ? "bigquery" : "";
            }
            JsonNode node = objectMapper.readTree(credentialsJson);
            return node.path("provider").asText("").toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }
}
