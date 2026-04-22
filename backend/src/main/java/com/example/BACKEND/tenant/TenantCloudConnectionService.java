package com.example.BACKEND.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Optional;

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


    private final TenantAuthService tenantAuthService;
    private final ObjectMapper objectMapper;

    public TenantCloudConnectionService(TenantAuthService tenantAuthService, ObjectMapper objectMapper) {
        this.tenantAuthService = tenantAuthService;
        this.objectMapper = objectMapper;
    }

    // ── BigQuery ──────────────────────────────────────────────────────────────

    public Optional<BigQueryConfig> getBigQueryConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<String> rawOpt = tenantAuthService.getCloudDbLink(tenantId);
        if (rawOpt.isEmpty()) return Optional.empty();
        String raw = rawOpt.get();
        if (raw == null || raw.isBlank()) return Optional.empty();
        return parseBigQueryConfig(tenantId.trim(), raw.trim());
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
            String serviceAccountJson = node.path("serviceAccountJson").asText("").trim();
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

    // ── Snowflake ─────────────────────────────────────────────────────────────

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

    public Optional<SnowflakeConfig> getSnowflakeConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return Optional.empty();
        Optional<String> rawOpt = tenantAuthService.getCloudDbLink(tenantId);
        if (rawOpt.isEmpty()) return Optional.empty();
        String raw = rawOpt.get();
        if (raw == null || raw.isBlank()) return Optional.empty();
        return parseSnowflakeConfig(tenantId.trim(), raw.trim());
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

    // ── Generic provider check ────────────────────────────────────────────────

    /** Returns the cloud provider key stored for a tenant, or empty string. */
    public String getProvider(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "";
        Optional<String> rawOpt = tenantAuthService.getCloudDbLink(tenantId);
        if (rawOpt.isEmpty()) return "";
        String raw = rawOpt.get();
        if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) return "";
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            return node.path("provider").asText("").toLowerCase().trim();
        } catch (Exception ex) {
            return "";
        }
    }
}
