package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.IdentityRepository;
import com.example.BACKEND.identity.connector.WorkspaceConnectorService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Cloud warehouse config access.
 *
 * Primary authority: {@code workspace_connectors} keyed by workspace UUID.
 * Legacy fallback: {@code user_credentials.cloud_db_link} via slug (read-only).
 */
@Service
public class TenantCloudConnectionService {

    public record BigQueryConfig(
            String tenantId,
            String projectId,
            String location,
            String dataset,
            String serviceAccountJson,
            String connectionLink
    ) {}

    public record SnowflakeConfig(
            String tenantId,
            String account,
            String warehouse,
            String database,
            String schema,
            String username,
            String password,
            String connectionLink
    ) {}

    public record PostgresConfig(
            String tenantId,
            String host,
            int port,
            String database,
            String schema,
            String username,
            String password,
            boolean ssl,
            String connectionLink
    ) {}

    private final WorkspaceConnectorService workspaceConnectors;
    private final IdentityRepository identities;
    private final ObjectMapper objectMapper;

    public TenantCloudConnectionService(
            WorkspaceConnectorService workspaceConnectors,
            IdentityRepository identities,
            ObjectMapper objectMapper
    ) {
        this.workspaceConnectors = workspaceConnectors;
        this.identities = identities;
        this.objectMapper = objectMapper;
    }

    public Optional<String> getRawConfig(UUID workspaceId) {
        return getRawConfig(workspaceId, null);
    }

    public Optional<String> getRawConfig(UUID workspaceId, String warehouseType) {
        if (workspaceId == null) {
            return Optional.empty();
        }
        return workspaceConnectors.getConnectorJson(workspaceId, warehouseType);
    }

    /**
     * Resolve raw connector JSON by workspace slug (DecisionRuntime / legacy callers).
     * Prefers workspace_connectors; falls back to user_credentials.
     */
    public Optional<String> getRawConfigBySlug(String tenantSlug) {
        return getRawConfigBySlug(tenantSlug, null);
    }

    public Optional<String> getRawConfigBySlug(String tenantSlug, String warehouseType) {
        if (tenantSlug == null || tenantSlug.isBlank()) {
            return Optional.empty();
        }
        return workspaceConnectors.getConnectorJsonLegacy(tenantSlug.trim(), warehouseType);
    }

    public void saveRawConfig(UUID workspaceId, String warehouseType, String credentialsJson,
                              String ipAddress, String userAgent) {
        workspaceConnectors.saveConnector(
                workspaceId,
                warehouseType == null || warehouseType.isBlank() ? "BIGQUERY" : warehouseType,
                credentialsJson,
                ipAddress,
                userAgent
        );
    }

    public Optional<BigQueryConfig> getBigQueryConfig(UUID workspaceId) {
        // Always resolve the BIGQUERY connector row — never a newer Snowflake/other row.
        return getRawConfig(workspaceId, "BIGQUERY")
                .filter(raw -> raw != null && !raw.isBlank())
                .flatMap(raw -> parseBigQueryConfig(workspaceId.toString(), raw.trim()));
    }

    /** Slug-keyed overload retained for DecisionRuntime / catalogue paths. */
    public Optional<BigQueryConfig> getBigQueryConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<UUID> wsId = identities.findWorkspaceIdBySlug(tenantId.trim());
        if (wsId.isPresent()) {
            Optional<BigQueryConfig> fromWs = getBigQueryConfig(wsId.get());
            if (fromWs.isPresent()) return fromWs;
        }
        return getRawConfigBySlug(tenantId, "BIGQUERY")
                .flatMap(raw -> parseBigQueryConfig(tenantId.trim(), raw.trim()));
    }

    private Optional<BigQueryConfig> parseBigQueryConfig(String tenantId, String raw) {
        try {
            if (!raw.startsWith("{")) {
                if (!raw.toLowerCase().startsWith("bigquery://")) return Optional.empty();
                return Optional.of(new BigQueryConfig(tenantId, "", "", "", "", raw));
            }
            JsonNode node = objectMapper.readTree(raw);
            String provider = node.path("provider").asText("");
            if (!"bigquery".equalsIgnoreCase(provider)) return Optional.empty();
            String projectId = node.path("projectId").asText("").trim();
            String location = node.path("location").asText("").trim();
            String dataset = node.path("dataset").asText("").trim();
            String serviceAccountJson = extractServiceAccountJson(node.get("serviceAccountJson"));
            if (serviceAccountJson.isBlank()) {
                serviceAccountJson = node.path("serviceAccountJson").asText("").trim();
            }
            String connectionLink = node.path("connectionLink").asText("").trim();
            return Optional.of(new BigQueryConfig(
                    tenantId,
                    projectId,
                    location,
                    dataset,
                    serviceAccountJson,
                    connectionLink
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public Optional<SnowflakeConfig> getSnowflakeConfig(UUID workspaceId) {
        return getRawConfig(workspaceId, "SNOWFLAKE")
                .filter(raw -> raw != null && !raw.isBlank())
                .flatMap(raw -> parseSnowflakeConfig(workspaceId.toString(), raw.trim()));
    }

    public Optional<SnowflakeConfig> getSnowflakeConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<UUID> wsId = identities.findWorkspaceIdBySlug(tenantId.trim());
        if (wsId.isPresent()) {
            Optional<SnowflakeConfig> fromWs = getSnowflakeConfig(wsId.get());
            if (fromWs.isPresent()) return fromWs;
        }
        return getRawConfigBySlug(tenantId, "SNOWFLAKE")
                .flatMap(raw -> parseSnowflakeConfig(tenantId.trim(), raw.trim()));
    }

    public Optional<PostgresConfig> getPostgresConfig(UUID workspaceId) {
        return getRawConfig(workspaceId, "POSTGRESQL")
                .filter(raw -> raw != null && !raw.isBlank())
                .flatMap(raw -> parsePostgresConfig(workspaceId.toString(), raw.trim()));
    }

    public Optional<PostgresConfig> getPostgresConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<UUID> wsId = identities.findWorkspaceIdBySlug(tenantId.trim());
        if (wsId.isPresent()) {
            Optional<PostgresConfig> fromWs = getPostgresConfig(wsId.get());
            if (fromWs.isPresent()) return fromWs;
        }
        return getRawConfigBySlug(tenantId, "POSTGRESQL")
                .flatMap(raw -> parsePostgresConfig(tenantId.trim(), raw.trim()));
    }

    private Optional<PostgresConfig> parsePostgresConfig(String tenantId, String raw) {
        try {
            if (!raw.startsWith("{")) return Optional.empty();
            JsonNode node = objectMapper.readTree(raw);
            String provider = node.path("provider").asText("");
            if (!"postgresql".equalsIgnoreCase(provider)) return Optional.empty();
            String host = node.path("host").asText("").trim();
            int port = PostgresConnectorService.DEFAULT_PORT;
            JsonNode portNode = node.get("port");
            if (portNode != null && !portNode.isNull() && !portNode.asText("").isBlank()) {
                try {
                    port = PostgresConnectorService.normalizePort(portNode.isNumber()
                            ? portNode.asInt()
                            : Integer.parseInt(portNode.asText().trim()));
                } catch (Exception ex) {
                    return Optional.empty();
                }
            }
            String schema = node.path("schema").asText("").trim();
            if (schema.isBlank()) {
                schema = PostgresConnectorService.DEFAULT_SCHEMA;
            }
            boolean ssl = node.path("ssl").asBoolean(false);
            return Optional.of(new PostgresConfig(
                    tenantId,
                    host,
                    port,
                    node.path("database").asText("").trim(),
                    schema,
                    node.path("username").asText("").trim(),
                    node.path("password").asText("").trim(),
                    ssl,
                    node.path("connectionLink").asText("").trim()
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private Optional<SnowflakeConfig> parseSnowflakeConfig(String tenantId, String raw) {
        try {
            if (!raw.startsWith("{")) return Optional.empty();
            JsonNode node = objectMapper.readTree(raw);
            String provider = node.path("provider").asText("");
            if (!"snowflake".equalsIgnoreCase(provider)) return Optional.empty();
            return Optional.of(new SnowflakeConfig(
                    tenantId,
                    node.path("account").asText("").trim(),
                    node.path("warehouse").asText("").trim(),
                    node.path("database").asText("").trim(),
                    node.path("schema").asText("PUBLIC").trim(),
                    node.path("username").asText("").trim(),
                    node.path("password").asText("").trim(),
                    node.path("connectionLink").asText("").trim()
            ));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String extractServiceAccountJson(JsonNode sa) {
        if (sa == null || sa.isNull() || sa.isMissingNode()) return "";
        if (sa.isTextual()) return sa.asText().trim();
        if (sa.isObject()) {
            try {
                return objectMapper.writeValueAsString(sa);
            } catch (JsonProcessingException ex) {
                return "";
            }
        }
        return sa.asText("").trim();
    }

    public String getProvider(UUID workspaceId) {
        // Active warehouse = most recently updated CONNECTED connector (any type).
        // Do not hard-prefer BIGQUERY or SNOWFLAKE — multi-warehouse must follow last save.
        return getRawConfig(workspaceId)
                .map(this::providerFromRaw)
                .orElse("");
    }

    /** Returns the cloud provider key stored for a tenant slug, or empty string. */
    public String getProvider(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "";
        Optional<UUID> wsId = identities.findWorkspaceIdBySlug(tenantId.trim());
        if (wsId.isPresent()) {
            String p = getProvider(wsId.get());
            if (!p.isBlank()) return p;
        }
        return getRawConfigBySlug(tenantId)
                .map(this::providerFromRaw)
                .orElse("");
    }

    private String providerFromRaw(String raw) {
        if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) return "";
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            return node.path("provider").asText("").toLowerCase().trim();
        } catch (Exception ex) {
            return "";
        }
    }
}
