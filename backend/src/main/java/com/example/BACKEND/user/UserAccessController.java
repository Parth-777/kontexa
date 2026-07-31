package com.example.BACKEND.user;

import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.example.BACKEND.tenant.TenantContextResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class UserAccessController {

    private final UserAccessService userAccessService;
    private final TenantCloudConnectionService cloudConnectionService;
    private final BigQueryConnectorService bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;
    private final TenantContextResolver tenantContextResolver;

    public UserAccessController(
            UserAccessService userAccessService,
            TenantCloudConnectionService cloudConnectionService,
            BigQueryConnectorService bigQueryConnectorService,
            SnowflakeConnectorService snowflakeConnectorService,
            TenantContextResolver tenantContextResolver
    ) {
        this.userAccessService = userAccessService;
        this.cloudConnectionService = cloudConnectionService;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/api/auth/user/login")
    public ResponseEntity<?> userLogin(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String password = body.get("password");
        if (userId == null || userId.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and password are required"));
        }

        return userAccessService.authenticate(userId.trim(), password.trim())
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(Map.of(
                        "accountType", "USER",
                        "userId", result.userId(),
                        "tenantId", result.tenantId(),
                        "position", result.position(),
                        "tenantSchema", result.tenantSchema()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid userId or password")));
    }

    @GetMapping("/api/tenant/users")
    public ResponseEntity<?> listUsers(@RequestParam(required = false) String tenantId) {
        String resolvedTenantId = tenantContextResolver.resolveClientId(null, tenantId);
        List<Map<String, Object>> users = userAccessService.listUsers(resolvedTenantId);
        return ResponseEntity.ok(Map.of("users", users));
    }

    @PostMapping("/api/tenant/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body) {
        String tenantId = tenantContextResolver.resolveClientId(null, body);
        String userId = asString(body.get("userId"));
        String password = asString(body.get("password"));
        String position = asString(body.get("position"));
        boolean active = body.get("active") == null || Boolean.parseBoolean(String.valueOf(body.get("active")));
        if (userId == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and password are required"));
        }
        userAccessService.createUser(tenantId, userId, password, position == null ? "Viewer" : position, active);
        return ResponseEntity.ok(Map.of("message", "User created"));
    }

    @PutMapping("/api/tenant/users/{userId}/role")
    public ResponseEntity<?> updateRole(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String tenantId = tenantContextResolver.resolveClientId(null, body);
        String position = body.get("position");
        if (position == null || position.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "position is required"));
        }
        try {
            userAccessService.updateRole(tenantId, userId.trim(), position.trim());
            return ResponseEntity.ok(Map.of("message", "Role updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/api/tenant/users/{userId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        String tenantId = tenantContextResolver.resolveClientId(null, body);
        boolean active = Boolean.parseBoolean(String.valueOf(body.get("active")));
        try {
            userAccessService.updateStatus(tenantId, userId.trim(), active);
            return ResponseEntity.ok(Map.of("message", "Status updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/api/tenant/users/{userId}")
    public ResponseEntity<?> deleteUser(
            @PathVariable String userId,
            @RequestParam(required = false) String tenantId) {
        String resolvedTenantId = tenantContextResolver.resolveClientId(null, tenantId);
        try {
            userAccessService.deleteUser(resolvedTenantId, userId.trim());
            return ResponseEntity.ok(Map.of("message", "User deleted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/user/tables")
    public ResponseEntity<?> listTables(
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String tenantId
    ) {
        // Tenant authority from session only. schema/tenantId query hints are mismatch-checked.
        String resolvedTenantId = tenantContextResolver.resolveClientId(null, tenantId);
        if (schema != null && !schema.isBlank()
                && !schema.trim().equalsIgnoreCase(resolvedTenantId)
                && !"public".equalsIgnoreCase(schema.trim())) {
            tenantContextResolver.assertHintMatches(resolvedTenantId, schema);
        }
        String provider = cloudConnectionService.getProvider(resolvedTenantId);

        try {
            if ("bigquery".equals(provider)) {
                var cfg = cloudConnectionService.getBigQueryConfig(resolvedTenantId);
                if (cfg.isPresent()) {
                    List<String> tables = bigQueryConnectorService.listTables(
                            cfg.get().projectId(),
                            cfg.get().serviceAccountJson(),
                            cfg.get().location(),
                            cfg.get().dataset()
                    );
                    return ResponseEntity.ok(Map.of("tables", tables, "source", "bigquery"));
                }
            }

            if ("snowflake".equals(provider)) {
                var cfg = cloudConnectionService.getSnowflakeConfig(resolvedTenantId);
                if (cfg.isPresent()) {
                    List<String> tables = snowflakeConnectorService.listTables(
                            cfg.get().account(),
                            cfg.get().warehouse(),
                            cfg.get().database(),
                            cfg.get().schema(),
                            cfg.get().username(),
                            cfg.get().password()
                    );
                    return ResponseEntity.ok(Map.of("tables", tables, "source", "snowflake"));
                }
            }

            // Local PostgreSQL: list schema named after authenticated tenant key (not client schema spoof).
            List<String> tables = userAccessService.listTablesForSchema(resolvedTenantId);
            return ResponseEntity.ok(Map.of("tables", tables, "source", "postgres"));

        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Failed to load tables: " + ex.getMessage()));
        }
    }

    private String asString(Object value) {
        if (value == null) return null;
        String out = String.valueOf(value).trim();
        return out.isEmpty() ? null : out;
    }
}
