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
}
