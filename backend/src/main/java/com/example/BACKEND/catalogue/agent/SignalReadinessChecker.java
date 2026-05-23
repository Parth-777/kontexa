package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks whether a tenant's data is ready for agent analysis before running
 * the full orchestration pipeline.
 *
 * Gates prevent agents from running on misconfigured tenants and wasting
 * LLM tokens on empty or unreachable data sources.
 *
 * Scoring (each check adds to the score):
 *   +40  Approved catalogue exists with at least one table
 *   +30  Data source is reachable (connectivity probe)
 *   +20  At least one table returns rows on a COUNT query
 *   +10  Analysis has never failed for this client (positive history)
 *
 * Score ≥ 60 = READY (agents will run)
 * Score < 60 = NOT READY (orchestrator skips, logs the reasons)
 */
@Service
public class SignalReadinessChecker {

    private static final int READY_THRESHOLD = 60;

    private final CatalogueSnapshotRepository snapshotRepo;
    private final InsightCardRepository       insightCardRepo;
    private final TenantCloudConnectionService cloudConnectionService;
    private final BigQueryConnectorService     bigQueryConnectorService;
    private final SnowflakeConnectorService    snowflakeConnectorService;
    private final JdbcTemplate                jdbcTemplate;
    private final ObjectMapper                objectMapper;

    public SignalReadinessChecker(
            CatalogueSnapshotRepository  snapshotRepo,
            InsightCardRepository        insightCardRepo,
            TenantCloudConnectionService cloudConnectionService,
            BigQueryConnectorService     bigQueryConnectorService,
            SnowflakeConnectorService    snowflakeConnectorService,
            JdbcTemplate                 jdbcTemplate,
            ObjectMapper                 objectMapper
    ) {
        this.snapshotRepo             = snapshotRepo;
        this.insightCardRepo          = insightCardRepo;
        this.cloudConnectionService   = cloudConnectionService;
        this.bigQueryConnectorService = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.jdbcTemplate             = jdbcTemplate;
        this.objectMapper             = objectMapper;
    }

    public ReadinessReport check(String clientId) {
        List<String> issues = new ArrayList<>();
        int score = 0;

        // ── Check 1: approved catalogue exists (+40) ───────────────────────
        var snapshotOpt = snapshotRepo.findByClientId(clientId);
        if (snapshotOpt.isEmpty()) {
            issues.add("No approved catalogue found for " + clientId);
            return new ReadinessReport(0, issues, false);
        }

        JsonNode catalogueNode = parseSnapshot(snapshotOpt.get().getCatalogueJson());
        if (catalogueNode == null) {
            issues.add("Catalogue snapshot is malformed");
            return new ReadinessReport(0, issues, false);
        }

        int tableCount = 0;
        for (JsonNode ignored : catalogueNode.path("tables")) tableCount++;

        if (tableCount == 0) {
            issues.add("Catalogue has no tables defined");
            return new ReadinessReport(0, issues, false);
        }

        score += 40;

        // ── Check 2: data source connectivity (+30) ────────────────────────
        String provider = cloudConnectionService.getProvider(clientId);
        Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg = Optional.empty();
        Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg = Optional.empty();

        if ("bigquery".equalsIgnoreCase(provider))   bqCfg = cloudConnectionService.getBigQueryConfig(clientId);
        if ("snowflake".equalsIgnoreCase(provider))  sfCfg = cloudConnectionService.getSnowflakeConfig(clientId);

        boolean useBQ = bqCfg.isPresent() && notBlank(bqCfg.get().projectId());
        boolean useSF = sfCfg.isPresent() && notBlank(sfCfg.get().account());

        // Probe: try a trivial query on the first table
        String firstTable = getFirstTableRef(catalogueNode, provider);
        boolean connected = false;
        if (firstTable != null) {
            connected = probeConnectivity(firstTable, provider, useBQ, useSF, bqCfg, sfCfg);
        }

        if (connected) {
            score += 30;
        } else {
            issues.add("Cannot reach data source (" + (provider != null ? provider : "PostgreSQL") + ")");
        }

        // ── Check 3: at least one table has rows (+20) ─────────────────────
        if (connected) {
            boolean hasRows = countRows(firstTable, provider, useBQ, useSF, bqCfg, sfCfg) > 0;
            if (hasRows) {
                score += 20;
            } else {
                issues.add("First table appears to be empty");
            }
        }

        // ── Check 4: positive history — previous successful analysis (+10) ─
        long pastCards = insightCardRepo
                .findByClientIdAndStatusOrderByGeneratedAtDesc(clientId, "AWAITING_CONFIRMATION")
                .size();
        if (pastCards > 0) {
            score += 10;
        }

        boolean ready = score >= READY_THRESHOLD;
        if (!ready && issues.isEmpty()) {
            issues.add("Readiness score " + score + " is below threshold " + READY_THRESHOLD);
        }

        return new ReadinessReport(score, issues, ready);
    }

    // ── Probe helpers ─────────────────────────────────────────────────────────

    private boolean probeConnectivity(String tableRef, String provider,
                                       boolean useBQ, boolean useSF,
                                       Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
                                       Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg) {
        try {
            String sql = "SELECT 1 FROM " + tableRef + " LIMIT 1";
            List<?> rows = runQuery(sql, provider, useBQ, useSF, bqCfg, sfCfg);
            return rows != null;
        } catch (Exception e) {
            return false;
        }
    }

    private long countRows(String tableRef, String provider,
                            boolean useBQ, boolean useSF,
                            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
                            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg) {
        try {
            String sql = "SELECT COUNT(*) AS cnt FROM " + tableRef;
            var rows = runQuery(sql, provider, useBQ, useSF, bqCfg, sfCfg);
            if (rows == null || rows.isEmpty()) return 0;
            Object cnt = ((java.util.Map<?, ?>) rows.get(0)).get("cnt");
            if (cnt instanceof Number n) return n.longValue();
            return Long.parseLong(cnt.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.List<java.util.Map<String, Object>> runQuery(
            String sql, String provider,
            boolean useBQ, boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg) {
        if (useBQ && bqCfg.isPresent()) {
            var c = bqCfg.get();
            return bigQueryConnectorService.executeSelect(
                    c.projectId(), c.serviceAccountJson(), c.location(), c.dataset(), sql);
        } else if (useSF && sfCfg.isPresent()) {
            var c = sfCfg.get();
            return snowflakeConnectorService.executeSelect(
                    c.account(), c.warehouse(), c.database(),
                    c.schema(), c.username(), c.password(), sql);
        } else {
            return jdbcTemplate.queryForList(sql);
        }
    }

    // ── Catalogue helpers ─────────────────────────────────────────────────────

    private String getFirstTableRef(JsonNode catalogueNode, String provider) {
        for (JsonNode t : catalogueNode.path("tables")) {
            String name   = t.path("tableName").asText("");
            String schema = t.path("tableSchema").asText("public");
            if (name.isBlank()) continue;
            return switch (provider == null ? "" : provider.toLowerCase()) {
                case "bigquery"  -> name;
                case "snowflake" -> (schema.isBlank() ? "PUBLIC" : schema.toUpperCase())
                                    + "." + name.toUpperCase();
                default          -> (schema.isBlank() ? "public" : schema) + "." + name;
            };
        }
        return null;
    }

    private JsonNode parseSnapshot(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return null; }
    }

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ── Result type ───────────────────────────────────────────────────────────

    public record ReadinessReport(int score, List<String> issues, boolean ready) {
        public String summary() {
            return ready
                ? "READY (score=" + score + ")"
                : "NOT READY (score=" + score + "): " + String.join("; ", issues);
        }
    }
}
