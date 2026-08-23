package com.example.BACKEND.tenant;

import com.example.BACKEND.identity.auth.AuthorizationService;
import com.example.BACKEND.identity.connector.WorkspaceConnectorService;
import com.example.BACKEND.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth/tenant")
public class TenantAuthController {

    private final TenantAuthService tenantAuthService;
    private final BigQueryConnectorService bigQueryConnectorService;
    private final SnowflakeConnectorService snowflakeConnectorService;
    private final PostgresConnectorService postgresConnectorService;
    private final ObjectMapper objectMapper;
    private final TenantContextResolver tenantContextResolver;
    private final TenantCloudConnectionService cloudConnectionService;
    private final WorkspaceConnectorService workspaceConnectorService;
    private final AuthorizationService authorization;

    public TenantAuthController(
            TenantAuthService tenantAuthService,
            BigQueryConnectorService bigQueryConnectorService,
            SnowflakeConnectorService snowflakeConnectorService,
            PostgresConnectorService postgresConnectorService,
            ObjectMapper objectMapper,
            TenantContextResolver tenantContextResolver,
            TenantCloudConnectionService cloudConnectionService,
            WorkspaceConnectorService workspaceConnectorService,
            AuthorizationService authorization
    ) {
        this.tenantAuthService = tenantAuthService;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.postgresConnectorService = postgresConnectorService;
        this.objectMapper = objectMapper;
        this.tenantContextResolver = tenantContextResolver;
        this.cloudConnectionService = cloudConnectionService;
        this.workspaceConnectorService = workspaceConnectorService;
        this.authorization = authorization;
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
    public ResponseEntity<?> getCloudLink(@RequestParam(required = false) String tenantId) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            return cloudConnectionService.getRawConfig(ctx.tenantId())
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey()))
                    .<ResponseEntity<?>>map(link -> ResponseEntity.ok(Map.of(
                            "tenantId", ctx.legacyTenantKey(),
                            "workspaceId", ctx.tenantId().toString(),
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
    public ResponseEntity<?> updateCloudLink(
            @RequestBody Map<String, String> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, body);
        String cloudDbLink = body.get("cloudDbLink");

        if (cloudDbLink == null || cloudDbLink.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudDbLink is required"));
        }

        try {
            String warehouseType = workspaceConnectorService.detectProvider(cloudDbLink);
            if (warehouseType == null || warehouseType.isBlank()) {
                warehouseType = "BIGQUERY";
            }
            cloudConnectionService.saveRawConfig(
                    ctx.tenantId(),
                    warehouseType.toUpperCase(),
                    cloudDbLink,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent")
            );
            return ResponseEntity.ok(Map.of(
                    "message", "Cloud database link updated",
                    "tenantId", ctx.legacyTenantKey(),
                    "workspaceId", ctx.tenantId().toString(),
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
        authorization.requireAdmin();
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
    public ResponseEntity<?> connectBigQuery(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, body);

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
            cloudConnectionService.saveRawConfig(
                    ctx.tenantId(), "BIGQUERY", configJson,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));

            // Verify warehouse path uses the same persisted credentials (not request-body only).
            var persisted = cloudConnectionService.getBigQueryConfig(ctx.tenantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "BigQuery credentials were saved but could not be reloaded for this workspace."));
            bigQueryConnectorService.testConnection(
                    persisted.projectId(),
                    persisted.serviceAccountJson(),
                    persisted.location(),
                    persisted.dataset()
            );

            return ResponseEntity.ok(Map.of(
                    "message", "BigQuery connection verified and saved",
                    "tenantId", ctx.legacyTenantKey(),
                    "workspaceId", ctx.tenantId().toString(),
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
    public ResponseEntity<?> getBigQueryConfig(@RequestParam(required = false) String tenantId) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            return cloudConnectionService.getRawConfig(ctx.tenantId(), "BIGQUERY")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "BIGQUERY"))
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
    public ResponseEntity<?> listBigQueryTables(@RequestParam(required = false) String tenantId) {
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            Optional<String> rawConfigOpt = cloudConnectionService.getRawConfig(ctx.tenantId(), "BIGQUERY")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "BIGQUERY"));
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

    // ── Snowflake connector endpoints ─────────────────────────────────────────

    @PostMapping("/snowflake/test-connection")
    public ResponseEntity<?> testSnowflakeConnection(@RequestBody Map<String, Object> body) {
        authorization.requireAdmin();
        try {
            SnowflakeConnectorService.SnowflakeTestResult result = snowflakeConnectorService.testConnection(
                    getAsString(body, "account"),
                    getAsString(body, "warehouse"),
                    getAsString(body, "database"),
                    getAsString(body, "schema"),
                    getAsString(body, "username"),
                    getAsString(body, "password")
            );
            return ResponseEntity.ok(Map.of(
                    "connected",      true,
                    "connectionLink", result.connectionLink(),
                    "account",        result.account(),
                    "warehouse",      result.warehouse(),
                    "database",       result.database(),
                    "schema",         result.schema()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/snowflake/connect")
    public ResponseEntity<?> connectSnowflake(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, body);
        try {
            SnowflakeConnectorService.SnowflakeTestResult result = snowflakeConnectorService.testConnection(
                    getAsString(body, "account"),
                    getAsString(body, "warehouse"),
                    getAsString(body, "database"),
                    getAsString(body, "schema"),
                    getAsString(body, "username"),
                    getAsString(body, "password")
            );
            String configJson = buildSnowflakeConfigJson(
                    result.account(),
                    result.warehouse(),
                    result.database(),
                    result.schema(),
                    getAsString(body, "username"),
                    getAsString(body, "password"),
                    result.connectionLink()
            );
            cloudConnectionService.saveRawConfig(
                    ctx.tenantId(), "SNOWFLAKE", configJson,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(Map.of(
                    "message",            "Snowflake connection verified and saved",
                    "tenantId",           ctx.legacyTenantKey(),
                    "workspaceId",        ctx.tenantId().toString(),
                    "provider",           "snowflake",
                    "account",            result.account(),
                    "warehouse",          result.warehouse(),
                    "database",           result.database(),
                    "schema",             result.schema(),
                    "connectionLink",     result.connectionLink(),
                    "credentialsStored",  true
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/snowflake/config")
    public ResponseEntity<?> getSnowflakeConfig(@RequestParam(required = false) String tenantId) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            return cloudConnectionService.getRawConfig(ctx.tenantId(), "SNOWFLAKE")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "SNOWFLAKE"))
                    .<ResponseEntity<?>>map(this::toSnowflakeConfigResponse)
                    .orElseGet(() -> ResponseEntity.ok(Map.of("connected", false, "provider", "snowflake")));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/snowflake/tables")
    public ResponseEntity<?> listSnowflakeTables(@RequestParam(required = false) String tenantId) {
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            Optional<String> rawConfigOpt = cloudConnectionService.getRawConfig(ctx.tenantId(), "SNOWFLAKE")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "SNOWFLAKE"));
            if (rawConfigOpt.isEmpty() || rawConfigOpt.get().isBlank()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No Snowflake connector configured for this tenant"));
            }
            StoredSnowflakeConfig cfg = parseStoredSnowflakeConfig(rawConfigOpt.get());
            List<String> tables = snowflakeConnectorService.listTables(
                    cfg.account(), cfg.warehouse(), cfg.database(), cfg.schema(), cfg.username(), cfg.password()
            );
            return ResponseEntity.ok(Map.of(
                    "connected",   true,
                    "provider",    "snowflake",
                    "account",     cfg.account(),
                    "warehouse",   cfg.warehouse(),
                    "database",    cfg.database(),
                    "schema",      cfg.schema(),
                    "tables",      tables,
                    "tableCount",  tables.size()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    // ── PostgreSQL connector endpoints ────────────────────────────────────────

    @PostMapping("/postgres/test-connection")
    public ResponseEntity<?> testPostgresConnection(@RequestBody Map<String, Object> body) {
        authorization.requireAdmin();
        try {
            PostgresConnectorService.PostgresTestResult result = postgresConnectorService.testConnection(
                    getAsString(body, "host"),
                    getAsInteger(body, "port"),
                    getAsString(body, "database"),
                    getAsString(body, "schema"),
                    getAsString(body, "username"),
                    getAsString(body, "password"),
                    getAsBoolean(body, "ssl", false)
            );
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "connectionLink", result.connectionLink(),
                    "host", result.host(),
                    "port", result.port(),
                    "database", result.database(),
                    "schema", result.schema(),
                    "ssl", result.ssl()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/postgres/connect")
    public ResponseEntity<?> connectPostgres(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, body);
        try {
            PostgresConnectorService.PostgresTestResult result = postgresConnectorService.testConnection(
                    getAsString(body, "host"),
                    getAsInteger(body, "port"),
                    getAsString(body, "database"),
                    getAsString(body, "schema"),
                    getAsString(body, "username"),
                    getAsString(body, "password"),
                    getAsBoolean(body, "ssl", false)
            );
            String configJson = buildPostgresConfigJson(
                    result.host(),
                    result.port(),
                    result.database(),
                    result.schema(),
                    getAsString(body, "username"),
                    getAsString(body, "password"),
                    result.ssl(),
                    result.connectionLink()
            );
            cloudConnectionService.saveRawConfig(
                    ctx.tenantId(), "POSTGRESQL", configJson,
                    request.getRemoteAddr(), request.getHeader("User-Agent"));

            var persisted = cloudConnectionService.getPostgresConfig(ctx.tenantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "PostgreSQL credentials were saved but could not be reloaded for this workspace."));
            postgresConnectorService.testConnection(
                    persisted.host(),
                    persisted.port(),
                    persisted.database(),
                    persisted.schema(),
                    persisted.username(),
                    persisted.password(),
                    persisted.ssl()
            );

            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("message", "PostgreSQL connection verified and saved");
            saved.put("tenantId", ctx.legacyTenantKey());
            saved.put("workspaceId", ctx.tenantId().toString());
            saved.put("provider", "postgresql");
            saved.put("host", result.host());
            saved.put("port", result.port());
            saved.put("database", result.database());
            saved.put("schema", result.schema());
            saved.put("ssl", result.ssl());
            saved.put("connectionLink", result.connectionLink());
            saved.put("credentialsStored", true);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/postgres/config")
    public ResponseEntity<?> getPostgresConfig(@RequestParam(required = false) String tenantId) {
        authorization.requireAdmin();
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            return cloudConnectionService.getRawConfig(ctx.tenantId(), "POSTGRESQL")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "POSTGRESQL"))
                    .<ResponseEntity<?>>map(this::toPostgresConfigResponse)
                    .orElseGet(() -> ResponseEntity.ok(Map.of("connected", false, "provider", "postgresql")));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/postgres/tables")
    public ResponseEntity<?> listPostgresTables(@RequestParam(required = false) String tenantId) {
        TenantContext ctx = tenantContextResolver.resolveContext(null, tenantId);
        try {
            Optional<String> rawConfigOpt = cloudConnectionService.getRawConfig(ctx.tenantId(), "POSTGRESQL")
                    .or(() -> cloudConnectionService.getRawConfigBySlug(ctx.legacyTenantKey(), "POSTGRESQL"));
            if (rawConfigOpt.isEmpty() || rawConfigOpt.get().isBlank()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No PostgreSQL connector configured for this tenant"));
            }
            StoredPostgresConfig cfg = parseStoredPostgresConfig(rawConfigOpt.get());
            List<String> tables = postgresConnectorService.listTables(
                    cfg.host(), cfg.port(), cfg.database(), cfg.schema(),
                    cfg.username(), cfg.password(), cfg.ssl()
            );
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "provider", "postgresql",
                    "host", cfg.host(),
                    "port", cfg.port(),
                    "database", cfg.database(),
                    "schema", cfg.schema(),
                    "tables", tables,
                    "tableCount", tables.size()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Snowflake helpers ─────────────────────────────────────────────────────

    private String buildSnowflakeConfigJson(
            String account, String warehouse, String database, String schema,
            String username, String password, String connectionLink
    ) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("provider",       "snowflake");
            root.put("account",        account    == null ? "" : account);
            root.put("warehouse",      warehouse  == null ? "" : warehouse);
            root.put("database",       database   == null ? "" : database);
            root.put("schema",         schema     == null ? "PUBLIC" : schema);
            root.put("username",       username   == null ? "" : username);
            root.put("password",       password   == null ? "" : password);
            root.put("connectionLink", connectionLink == null ? "" : connectionLink);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize Snowflake config", ex);
        }
    }

    private ResponseEntity<?> toSnowflakeConfigResponse(String rawCloudConfig) {
        if (rawCloudConfig == null || rawCloudConfig.isBlank()) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "snowflake"));
        }
        String raw = rawCloudConfig.trim();
        if (!raw.startsWith("{")) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "snowflake"));
        }
        try {
            var node = objectMapper.readTree(raw);
            if (!"snowflake".equalsIgnoreCase(node.path("provider").asText(""))) {
                return ResponseEntity.ok(Map.of("connected", false, "provider", "snowflake"));
            }
            return ResponseEntity.ok(Map.of(
                    "connected",          true,
                    "provider",           "snowflake",
                    "account",            node.path("account").asText(""),
                    "warehouse",          node.path("warehouse").asText(""),
                    "database",           node.path("database").asText(""),
                    "schema",             node.path("schema").asText("PUBLIC"),
                    "connectionLink",     node.path("connectionLink").asText(""),
                    "credentialsStored",  !node.path("username").asText("").isBlank()
            ));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "snowflake"));
        }
    }

    private StoredSnowflakeConfig parseStoredSnowflakeConfig(String rawConfig) {
        String raw = rawConfig == null ? "" : rawConfig.trim();
        if (raw.isBlank()) throw new IllegalArgumentException("Snowflake connector config is empty");
        if (!raw.startsWith("{")) throw new IllegalArgumentException("Stored connector config is not JSON; reconnect Snowflake.");
        try {
            var node = objectMapper.readTree(raw);
            if (!"snowflake".equalsIgnoreCase(node.path("provider").asText(""))) {
                throw new IllegalArgumentException("Stored connector is not Snowflake");
            }
            String account   = node.path("account").asText("").trim();
            String warehouse = node.path("warehouse").asText("").trim();
            String database  = node.path("database").asText("").trim();
            String schema    = node.path("schema").asText("PUBLIC").trim();
            String username  = node.path("username").asText("").trim();
            String password  = node.path("password").asText("").trim();
            if (account.isBlank())   throw new IllegalArgumentException("Stored Snowflake account is missing");
            if (database.isBlank())  throw new IllegalArgumentException("Stored Snowflake database is missing");
            if (username.isBlank())  throw new IllegalArgumentException("Stored Snowflake username is missing");
            return new StoredSnowflakeConfig(account, warehouse, database, schema, username, password);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stored Snowflake config is invalid JSON", ex);
        }
    }

    private record StoredSnowflakeConfig(
            String account, String warehouse, String database, String schema, String username, String password
    ) {}

    // ── PostgreSQL helpers ────────────────────────────────────────────────────

    private String buildPostgresConfigJson(
            String host, int port, String database, String schema,
            String username, String password, boolean ssl, String connectionLink
    ) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("provider", "postgresql");
            root.put("host", host == null ? "" : host);
            root.put("port", port);
            root.put("database", database == null ? "" : database);
            root.put("schema", schema == null || schema.isBlank()
                    ? PostgresConnectorService.DEFAULT_SCHEMA : schema);
            root.put("username", username == null ? "" : username);
            root.put("password", password == null ? "" : password);
            root.put("ssl", ssl);
            root.put("connectionLink", connectionLink == null ? "" : connectionLink);
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize PostgreSQL config", ex);
        }
    }

    private ResponseEntity<?> toPostgresConfigResponse(String rawCloudConfig) {
        if (rawCloudConfig == null || rawCloudConfig.isBlank()) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "postgresql"));
        }
        String raw = rawCloudConfig.trim();
        if (!raw.startsWith("{")) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "postgresql"));
        }
        try {
            var node = objectMapper.readTree(raw);
            if (!"postgresql".equalsIgnoreCase(node.path("provider").asText(""))) {
                return ResponseEntity.ok(Map.of("connected", false, "provider", "postgresql"));
            }
            int port = PostgresConnectorService.DEFAULT_PORT;
            JsonNode portNode = node.get("port");
            if (portNode != null && !portNode.isNull() && !portNode.asText("").isBlank()) {
                try {
                    port = PostgresConnectorService.normalizePort(portNode.isNumber()
                            ? portNode.asInt()
                            : Integer.parseInt(portNode.asText().trim()));
                } catch (Exception ignored) {
                    port = PostgresConnectorService.DEFAULT_PORT;
                }
            }
            String schema = node.path("schema").asText("").trim();
            if (schema.isBlank()) {
                schema = PostgresConnectorService.DEFAULT_SCHEMA;
            }
            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "provider", "postgresql",
                    "host", node.path("host").asText(""),
                    "port", port,
                    "database", node.path("database").asText(""),
                    "schema", schema,
                    "ssl", node.path("ssl").asBoolean(false),
                    "connectionLink", node.path("connectionLink").asText(""),
                    "credentialsStored", !node.path("username").asText("").isBlank()
            ));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of("connected", false, "provider", "postgresql"));
        }
    }

    private StoredPostgresConfig parseStoredPostgresConfig(String rawConfig) {
        String raw = rawConfig == null ? "" : rawConfig.trim();
        if (raw.isBlank()) throw new IllegalArgumentException("PostgreSQL connector config is empty");
        if (!raw.startsWith("{")) throw new IllegalArgumentException("Stored connector config is not JSON; reconnect PostgreSQL.");
        try {
            var node = objectMapper.readTree(raw);
            if (!"postgresql".equalsIgnoreCase(node.path("provider").asText(""))) {
                throw new IllegalArgumentException("Stored connector is not PostgreSQL");
            }
            String host = node.path("host").asText("").trim();
            int port = PostgresConnectorService.DEFAULT_PORT;
            JsonNode portNode = node.get("port");
            if (portNode != null && !portNode.isNull() && !portNode.asText("").isBlank()) {
                port = PostgresConnectorService.normalizePort(portNode.isNumber()
                        ? portNode.asInt()
                        : Integer.parseInt(portNode.asText().trim()));
            }
            String database = node.path("database").asText("").trim();
            String schema = node.path("schema").asText("").trim();
            if (schema.isBlank()) {
                schema = PostgresConnectorService.DEFAULT_SCHEMA;
            }
            String username = node.path("username").asText("").trim();
            String password = node.path("password").asText("").trim();
            boolean ssl = node.path("ssl").asBoolean(false);
            if (host.isBlank()) throw new IllegalArgumentException("Stored PostgreSQL host is missing");
            if (database.isBlank()) throw new IllegalArgumentException("Stored PostgreSQL database is missing");
            if (username.isBlank()) throw new IllegalArgumentException("Stored PostgreSQL username is missing");
            return new StoredPostgresConfig(host, port, database, schema, username, password, ssl);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stored PostgreSQL config is invalid JSON", ex);
        }
    }

    private record StoredPostgresConfig(
            String host, int port, String database, String schema, String username, String password, boolean ssl
    ) {}

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String getAsString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        return String.valueOf(value).trim();
    }

    private Integer getAsInteger(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private boolean getAsBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        Object value = body.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean b) return b;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return defaultValue;
        return Boolean.parseBoolean(text);
    }

    private String extractStoredServiceAccountJson(com.fasterxml.jackson.databind.JsonNode configNode) {
        com.fasterxml.jackson.databind.JsonNode sa = configNode.get("serviceAccountJson");
        if (sa == null || sa.isNull()) return "";
        if (sa.isTextual()) return sa.asText().trim();
        if (sa.isObject()) {
            try {
                return objectMapper.writeValueAsString(sa);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Stored service account JSON is invalid", ex);
            }
        }
        return sa.asText("").trim();
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
            String serviceAccountJson = extractStoredServiceAccountJson(node);
            if (projectId.isBlank()) throw new IllegalArgumentException("Stored BigQuery projectId is missing");
            if (dataset.isBlank()) throw new IllegalArgumentException("Stored BigQuery dataset is missing");
            if (serviceAccountJson.isBlank()) {
                throw new IllegalArgumentException(
                        "Stored BigQuery service account is missing or corrupted. "
                                + "Reconnect using a fresh JSON key from GCP.");
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
