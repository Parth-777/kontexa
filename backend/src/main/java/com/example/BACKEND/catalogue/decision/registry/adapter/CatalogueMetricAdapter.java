package com.example.BACKEND.catalogue.decision.registry.adapter;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.entity.CatalogueColumnEntity;
import com.example.BACKEND.catalogue.entity.CatalogueTableEntity;
import com.example.BACKEND.catalogue.entity.ClientCatalogueEntity;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives {@link MetricDescriptor}s from the approved catalogue's columns
 * that have been labelled with role="metric" by the enrichment pipeline.
 *
 * Falls back to numeric columns when no explicit metric role is assigned.
 */
@Component
public class CatalogueMetricAdapter {

    private static final List<String> NUMERIC_TYPES = List.of("integer", "int", "int4", "int8",
                                                               "bigint", "smallint", "numeric",
                                                               "decimal", "real", "double",
                                                               "float", "number");

    private final CatalogueApprovalService approvalService;

    public CatalogueMetricAdapter(CatalogueApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Returns metric descriptors relevant for the given objective.
     * The objectiveKey is currently unused in Phase 1 — all numeric columns
     * are returned. Later phases can filter by tagging/metadata.
     */
    public List<MetricDescriptor> metricsForObjective(String objectiveKey, String tenantId) {
        ClientCatalogueEntity catalogue = latestApproved(tenantId);
        List<MetricDescriptor> metrics = new ArrayList<>();

        for (CatalogueTableEntity table : catalogue.getTables()) {
            for (CatalogueColumnEntity col : table.getColumns()) {
                if (isMetricColumn(col)) {
                    String key        = table.getTableName() + "." + col.getColumnName();
                    String aggType    = inferAggregation(objectiveKey);
                    String expression = aggType + "(" + key + ")";
                    metrics.add(new MetricDescriptor(key, expression, "NUMERIC", aggType, ""));
                }
            }
        }
        return metrics;
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private ClientCatalogueEntity latestApproved(String tenantId) {
        return approvalService.listForClient(tenantId).stream()
                .filter(c -> "APPROVED".equalsIgnoreCase(c.getStatus()))
                .max(Comparator.comparing(
                        c -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt()))
                .orElseThrow(() -> new IllegalStateException(
                        "No approved catalogue found for tenant: " + tenantId));
    }

    private boolean isMetricColumn(CatalogueColumnEntity col) {
        if ("metric".equalsIgnoreCase(col.getRole())) return true;
        String t = col.getDataType() == null ? "" : col.getDataType().toLowerCase();
        return NUMERIC_TYPES.stream().anyMatch(t::contains);
    }

    private String inferAggregation(String objectiveKey) {
        if (objectiveKey == null) return "SUM";
        return switch (objectiveKey) {
            case "ANOMALY_DETECTION"     -> "AVG";
            case "DISTRIBUTION_ANALYSIS" -> "COUNT";
            case "TREND_ANALYSIS"        -> "SUM";
            default                      -> "SUM";
        };
    }
}
