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
 * Adapts the existing {@link CatalogueApprovalService} (which holds the
 * approved, enriched schema) into the decision runtime's registry contracts.
 *
 * Uses the most recently updated APPROVED catalogue for the tenant,
 * so it is resilient to tenants having multiple approved catalogue versions.
 */
@Component
public class SchemaDiscoveryAdapter {

    private static final List<String> DATE_TYPES    = List.of("date", "timestamp", "timestamptz",
                                                               "timestamp without time zone",
                                                               "timestamp with time zone", "datetime");
    private static final List<String> NUMERIC_TYPES = List.of("integer", "int", "int4", "int8",
                                                               "bigint", "smallint", "numeric",
                                                               "decimal", "real", "double precision",
                                                               "float", "float4", "float8", "number");
    private static final List<String> TEXT_TYPES    = List.of("text", "varchar", "character varying",
                                                               "char", "string", "bpchar");

    private final CatalogueApprovalService approvalService;

    public SchemaDiscoveryAdapter(CatalogueApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Returns one {@link EntityDescriptor} per approved table.
     */
    public List<EntityDescriptor> discoverEntities(String tenantId) {
        ClientCatalogueEntity catalogue = latestApproved(tenantId);
        List<EntityDescriptor> entities = new ArrayList<>();

        for (CatalogueTableEntity table : catalogue.getTables()) {
            entities.add(new EntityDescriptor(
                    table.getTableName(),
                    table.getTableName(),
                    inferGrainKeys(table),
                    inferSemanticTags(table)
            ));
        }
        return entities;
    }

    /**
     * Returns {@link DimensionDescriptor}s: categorical and temporal columns
     * suitable for GROUP BY / time-series slicing.
     */
    public List<DimensionDescriptor> discoverDimensions(String tenantId, String objectiveKey) {
        ClientCatalogueEntity catalogue = latestApproved(tenantId);
        boolean wantTemporal = objectiveKey.contains("TREND") || objectiveKey.contains("ANOMALY");
        List<DimensionDescriptor> dims = new ArrayList<>();

        for (CatalogueTableEntity table : catalogue.getTables()) {
            for (CatalogueColumnEntity col : table.getColumns()) {
                String typeLower = col.getDataType() == null ? "" : col.getDataType().toLowerCase();
                String ref = table.getTableName() + "." + col.getColumnName();

                if (isDateType(typeLower) && wantTemporal) {
                    dims.add(new DimensionDescriptor(ref, ref, "TEMPORAL"));
                } else if (isTextType(typeLower)) {
                    dims.add(new DimensionDescriptor(ref, ref, "CATEGORICAL"));
                }
            }
        }
        return dims;
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Picks the most recently updated APPROVED catalogue for the tenant.
     * Handles the case where a tenant has multiple approved catalogue versions
     * (e.g. after re-running schema discovery) without throwing NonUniqueResultException.
     */
    private ClientCatalogueEntity latestApproved(String tenantId) {
        return approvalService.listForClient(tenantId).stream()
                .filter(c -> "APPROVED".equalsIgnoreCase(c.getStatus()))
                .max(Comparator.comparing(
                        c -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt()))
                .orElseThrow(() -> new IllegalStateException(
                        "No approved catalogue found for tenant: " + tenantId
                        + ". Please approve a catalogue in the admin dashboard."));
    }

    private List<String> inferGrainKeys(CatalogueTableEntity table) {
        List<String> grains = new ArrayList<>();
        for (CatalogueColumnEntity col : table.getColumns()) {
            String lower = col.getColumnName().toLowerCase();
            if (lower.endsWith("_id") || lower.equals("id")) grains.add(col.getColumnName());
        }
        if (grains.isEmpty() && !table.getColumns().isEmpty()) {
            grains.add(table.getColumns().get(0).getColumnName());
        }
        return grains;
    }

    private List<String> inferSemanticTags(CatalogueTableEntity table) {
        List<String> tags = new ArrayList<>();
        for (CatalogueColumnEntity col : table.getColumns()) {
            String t = col.getDataType() == null ? "" : col.getDataType().toLowerCase();
            if (isNumericType(t)) tags.add("has_metrics");
            if (isDateType(t))    tags.add("has_time");
            if (isTextType(t))    tags.add("has_dimensions");
            // honour catalogue-assigned role if available
            if ("metric".equalsIgnoreCase(col.getRole()))    tags.add("has_metrics");
            if ("timestamp".equalsIgnoreCase(col.getRole())) tags.add("has_time");
            if ("dimension".equalsIgnoreCase(col.getRole())) tags.add("has_dimensions");
        }
        return tags.stream().distinct().toList();
    }

    private boolean isDateType(String t)    { return DATE_TYPES.stream().anyMatch(t::contains); }
    private boolean isNumericType(String t) { return NUMERIC_TYPES.stream().anyMatch(t::contains); }
    private boolean isTextType(String t)    { return TEXT_TYPES.stream().anyMatch(t::contains); }
}
