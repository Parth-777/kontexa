package com.example.BACKEND.tenant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth/tenant")
public class TenantAuthController {

    private final TenantAuthService tenantAuthService;
    private final BigQueryConnectorService bigQueryConnectorService;
    private final ObjectMapper objectMapper;

    public TenantAuthController(
            TenantAuthService tenantAuthService,
            BigQueryConnectorService bigQueryConnectorService,
            ObjectMapper objectMapper
    ) {
        this.tenantAuthService = tenantAuthService;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String password = body.get("password");

        if (userId == null || userId.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and password are required"));
        }

        return tenantAuthService.authenticate(userId, password)
                .<ResponseEntity<?>>map(auth -> ResponseEntity.ok(Map.of(
                        "accountType", "TENANT",
                        "tenantId", auth.tenantId(),
                        "userId", auth.userId(),
                        "tenantSchema", auth.tenantSchema(),
                        "cloudDbLink", auth.cloudDbLink() == null ? "" : auth.cloudDbLink()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid userId or password")));
    }

    @GetMapping("/cloud-link")
    public ResponseEntity<?> getCloudLink(@RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }

        try {
            return tenantAuthService.getCloudDbLink(tenantId)
                    .<ResponseEntity<?>>map(link -> ResponseEntity.ok(Map.of(
                            "tenantId", tenantId.trim(),
                            "cloudDbLink", link
                    )))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("error", "Tenant not found")));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/cloud-link")
    public ResponseEntity<?> updateCloudLink(@RequestBody Map<String, String> body) {
        String tenantId = body.get("tenantId");
        String cloudDbLink = body.get("cloudDbLink");

        if (tenantId == null || tenantId.isBlank() || cloudDbLink == null || cloudDbLink.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId and cloudDbLink are required"));
        }

        try {
            tenantAuthService.updateCloudDbLink(tenantId, cloudDbLink);
            return ResponseEntity.ok(Map.of(
                    "message", "Cloud database link updated",
                    "tenantId", tenantId.trim(),
                    "cloudDbLink", cloudDbLink.trim()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/bigquery/test-connection")
    public ResponseEntity<?> testBigQueryConnection(@RequestBody Map<String, Object> body) {
        try {
            BigQueryConnectorService.BigQueryTestResult result = bigQueryConnectorService.testConnection(
                    getAsString(body, "projectId"),
                    extractServiceAccountJson(body),
                    getAsString(body, "location"),
                    getAsString(body, "dataset")
            );

            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "connectionLink", result.connectionLink(),
                    "projectId", result.projectId(),
                    "location", result.location(),
                    "dataset", result.dataset()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/bigquery/connect")
    public ResponseEntity<?> connectBigQuery(@RequestBody Map<String, Object> body) {
        String tenantId = getAsString(body, "tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }

        try {
            BigQueryConnectorService.BigQueryTestResult result = bigQueryConnectorService.testConnection(
                    getAsString(body, "projectId"),
                    extractServiceAccountJson(body),
                    getAsString(body, "location"),
                    getAsString(body, "dataset")
            );

            String serviceAccountJson = extractServiceAccountJson(body);
            String configJson = buildBigQueryConfigJson(
                    result.projectId(),
                    result.location(),
                    result.dataset(),
                    result.connectionLink(),
                    serviceAccountJson
            );
            tenantAuthService.updateCloudDbLink(tenantId, configJson);

            return ResponseEntity.ok(Map.of(
                    "message", "BigQuery connection verified and saved",
                    "tenantId", tenantId.trim(),
                    "cloudDbLink", result.connectionLink(),
                    "provider", "bigquery",
                    "projectId", result.projectId(),
                    "location", result.location(),
                    "dataset", result.dataset(),
                    "serviceAccountStored", true
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/bigquery/config")
    public ResponseEntity<?> getBigQueryConfig(@RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        try {
            return tenantAuthService.getCloudDbLink(tenantId)
                    .<ResponseEntity<?>>map(this::toBigQueryConfigResponse)
                    .orElseGet(() -> ResponseEntity.ok(Map.of(
                            "connected", false,
                            "provider", "bigquery"
                    )));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/bigquery/tables")
    public ResponseEntity<?> listBigQueryTables(@RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        try {
            Optional<String> rawConfigOpt = tenantAuthService.getCloudDbLink(tenantId);
            if (rawConfigOpt.isEmpty() || rawConfigOpt.get().isBlank()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No BigQuery connector configured for this tenant"));
            }

            StoredBigQueryConfig cfg = parseStoredBigQueryConfig(rawConfigOpt.get());
            List<String> tables = bigQueryConnectorService.listTables(
                    cfg.projectId(),
                    cfg.serviceAccountJson(),
                    cfg.location(),
                    cfg.dataset()
            );
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "provider", "bigquery",
                    "projectId", cfg.projectId(),
                    "location", cfg.location() == null ? "" : cfg.location(),
                    "dataset", cfg.dataset(),
                    "tables", tables,
                    "tableCount", tables.size()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    private String getAsString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        return String.valueOf(value).trim();
    }

    private String extractServiceAccountJson(Map<String, Object> body) {
        Object raw = body.get("serviceAccountJson");
        if (raw == null) return null;
        if (raw instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("serviceAccountJson must be valid JSON content", ex);
        }
    }

    private String buildBigQueryConfigJson(
            String projectId,
            String location,
            String dataset,
            String connectionLink,
            String serviceAccountJson
    ) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("provider", "bigquery");
            root.put("projectId", projectId == null ? "" : projectId);
            root.put("location", location == null ? "" : location);
            root.put("dataset", dataset == null ? "" : dataset);
            root.put("connectionLink", connectionLink == null ? "" : connectionLink);
            root.put("serviceAccountJson", serviceAccountJson == null ? "" : serviceAccountJson);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize BigQuery config", ex);
        }
    }

    private ResponseEntity<?> toBigQueryConfigResponse(String rawCloudConfig) {
        if (rawCloudConfig == null || rawCloudConfig.isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "provider", "bigquery"
            ));
        }
        String raw = rawCloudConfig.trim();
        if (!raw.startsWith("{")) {
            boolean looksLikeBigQueryLink = raw.toLowerCase().startsWith("bigquery://");
            return ResponseEntity.ok(Map.of(
                    "connected", looksLikeBigQueryLink,
                    "provider", "bigquery",
                    "connectionLink", looksLikeBigQueryLink ? raw : "",
                    "serviceAccountStored", false
            ));
        }
        try {
            var node = objectMapper.readTree(raw);
            String provider = node.path("provider").asText("");
            if (!"bigquery".equalsIgnoreCase(provider)) {
                return ResponseEntity.ok(Map.of(
                        "connected", false,
                        "provider", "bigquery"
                ));
            }
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "provider", "bigquery",
                    "projectId", node.path("projectId").asText(""),
                    "location", node.path("location").asText(""),
                    "dataset", node.path("dataset").asText(""),
                    "connectionLink", node.path("connectionLink").asText(""),
                    "serviceAccountStored", !node.path("serviceAccountJson").asText("").isBlank()
            ));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "connected", false,
                    "provider", "bigquery"
            ));
        }
    }

    private StoredBigQueryConfig parseStoredBigQueryConfig(String rawConfig) {
        String raw = rawConfig == null ? "" : rawConfig.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("BigQuery connector config is empty");
        }
        if (!raw.startsWith("{")) {
            throw new IllegalArgumentException("Stored connector config is legacy link-only format; reconnect BigQuery.");
        }
        try {
            var node = objectMapper.readTree(raw);
            String provider = node.path("provider").asText("");
            if (!"bigquery".equalsIgnoreCase(provider)) {
                throw new IllegalArgumentException("Stored connector is not BigQuery");
            }
            String projectId = node.path("projectId").asText("").trim();
            String dataset = node.path("dataset").asText("").trim();
            String location = node.path("location").asText("").trim();
            String serviceAccountJson = node.path("serviceAccountJson").asText("").trim();
            if (projectId.isBlank()) throw new IllegalArgumentException("Stored BigQuery projectId is missing");
            if (dataset.isBlank()) throw new IllegalArgumentException("Stored BigQuery dataset is missing");
            if (serviceAccountJson.isBlank()) {
                throw new IllegalArgumentException("Stored BigQuery service account is missing");
            }
            return new StoredBigQueryConfig(projectId, location, dataset, serviceAccountJson);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stored connector config is invalid JSON", ex);
        }
    }

    private record StoredBigQueryConfig(
            String projectId,
            String location,
            String dataset,
            String serviceAccountJson
    ) {}
}
