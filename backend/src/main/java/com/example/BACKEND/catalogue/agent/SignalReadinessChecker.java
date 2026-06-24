package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.repository.CatalogueSnapshotRepository;
import com.example.BACKEND.catalogue.repository.InsightCardRepository;
import com.example.BACKEND.tenant.BigQueryConnectorService;
import com.example.BACKEND.tenant.SnowflakeConnectorService;
import com.example.BACKEND.tenant.TenantCloudConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks whether a tenant's data is ready for agent analysis before running
 * the full orchestration pipeline.
 */
@Service
public class SignalReadinessChecker {

    private static final int READY_THRESHOLD = 60;

    private final CatalogueSnapshotRepository snapshotRepo;
    private final InsightCardRepository       insightCardRepo;
    private final TenantCloudConnectionService cloudConnectionService;
    private final BigQueryConnectorService     bigQueryConnectorService;
    private final SnowflakeConnectorService    snowflakeConnectorService;
    private final ObjectMapper                objectMapper;

    public SignalReadinessChecker(
            CatalogueSnapshotRepository  snapshotRepo,
            InsightCardRepository        insightCardRepo,
            TenantCloudConnectionService cloudConnectionService,
            BigQueryConnectorService     bigQueryConnectorService,
            SnowflakeConnectorService    snowflakeConnectorService,
            ObjectMapper                 objectMapper
    ) {
        this.snapshotRepo              = snapshotRepo;
        this.insightCardRepo           = insightCardRepo;
        this.cloudConnectionService    = cloudConnectionService;
        this.bigQueryConnectorService  = bigQueryConnectorService;
        this.snowflakeConnectorService = snowflakeConnectorService;
        this.objectMapper              = objectMapper;
    }

    public ReadinessReport check(String clientId) {
        List<String> issues = new ArrayList<>();
        int score = 0;

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

        String provider = cloudConnectionService.getProvider(clientId);
        Optional<TenantCloudConnectionService.BigQueryConfig>  bqCfg = Optional.empty();
        Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg = Optional.empty();

        if ("bigquery".equalsIgnoreCase(provider)) {
            bqCfg = cloudConnectionService.getBigQueryConfig(clientId);
        } else if ("snowflake".equalsIgnoreCase(provider)) {
            sfCfg = cloudConnectionService.getSnowflakeConfig(clientId);
        }

        boolean useBQ = bqCfg.isPresent() && notBlank(bqCfg.get().projectId())
                && notBlank(bqCfg.get().serviceAccountJson());
        boolean useSF = sfCfg.isPresent() && notBlank(sfCfg.get().account());

        boolean connected = probeConnectivity(clientId, provider, useBQ, useSF, bqCfg, sfCfg);

        if (connected) {
            score += 30;
        } else {
            if (useBQ && bqCfg.isEmpty()) {
                issues.add("BigQuery is not configured — reconnect in Settings");
            } else if (useSF && sfCfg.isEmpty()) {
                issues.add("Snowflake is not configured — reconnect in Settings");
            } else {
                issues.add("Cannot reach data source (" + (provider != null ? provider : "PostgreSQL") + ")");
            }
        }

        long catalogueRowCount = maxRowCountInCatalogue(catalogueNode);
        if (connected) {
            if (catalogueRowCount > 0) {
                score += 20;
            } else {
                issues.add("Catalogue shows no rows — re-approve catalogue after data load");
            }
        }

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

    /**
     * Lightweight reachability — same as Test Connection (SELECT 1), no table scan on 2M-row tables.
     */
    private boolean probeConnectivity(
            String clientId,
            String provider,
            boolean useBQ,
            boolean useSF,
            Optional<TenantCloudConnectionService.BigQueryConfig> bqCfg,
            Optional<TenantCloudConnectionService.SnowflakeConfig> sfCfg
    ) {
        try {
            if (useBQ && bqCfg.isPresent()) {
                var c = bqCfg.get();
                bigQueryConnectorService.testConnection(
                        c.projectId(), c.serviceAccountJson(), c.location(), c.dataset());
                return true;
            }
            if (useSF && sfCfg.isPresent()) {
                var c = sfCfg.get();
                snowflakeConnectorService.testConnection(
                        c.account(), c.warehouse(), c.database(), c.schema(),
                        c.username(), c.password());
                return true;
            }
            // Local PostgreSQL tenant schema
            return true;
        } catch (Exception e) {
            System.out.printf("[Readiness] Connectivity probe failed for %s: %s%n",
                    clientId, e.getMessage());
            return false;
        }
    }

    private long maxRowCountInCatalogue(JsonNode catalogueNode) {
        long max = 0;
        for (JsonNode t : catalogueNode.path("tables")) {
            max = Math.max(max, t.path("rowCount").asLong(0));
        }
        return max;
    }

    private JsonNode parseSnapshot(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public record ReadinessReport(int score, List<String> issues, boolean ready) {
        public String summary() {
            return ready
                    ? "READY (score=" + score + ")"
                    : "NOT READY (score=" + score + "): " + String.join("; ", issues);
        }
    }
}
