package com.example.BACKEND.tenant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

            tenantAuthService.updateCloudDbLink(tenantId, result.connectionLink());

            return ResponseEntity.ok(Map.of(
                    "message", "BigQuery connection verified and saved",
                    "tenantId", tenantId.trim(),
                    "cloudDbLink", result.connectionLink(),
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
}
