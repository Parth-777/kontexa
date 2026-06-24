package com.example.BACKEND.catalogue.decision.execution.framework;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Constructs business entities dynamically from raw rows using a schema profile
 * and computation blueprint.
 *
 * Entity construction is SCHEMA-DRIVEN — no domain-specific column names.
 *
 * Strategy:
 *   depth=1  → group rows by the lowest-cardinality dimension column
 *   depth=2  → group rows by a composite of the two lowest-cardinality dimensions
 *              (produces composite entities like A→B, which may be routes, accounts→segments,
 *              regions→products, or any dimensional pair in any dataset)
 *
 * For each constructed entity:
 *   - VALUE columns are SUM-aggregated (total output for that entity)
 *   - VOLUME columns are SUM-aggregated (total activity count)
 *   - RATE columns are AVG-aggregated (average rate for that entity)
 *   - Other numeric columns are AVG-aggregated
 *
 * Entities with fewer than {@code blueprint.minimumSamplePerEntity()} rows
 * are filtered before output.
 */
@Component
public class EntityExpansionEngine {

    private static final int MAX_ENTITIES = 100; // hard cap for memory safety

    public List<ConstructedEntity> expand(
            List<Map<String, Object>> rows,
            SchemaProfile             profile,
            ComputationBlueprint      blueprint
    ) {
        if (rows == null || rows.isEmpty() || !profile.hasDimensions())
            return List.of();

        // Select grouping dimensions based on blueprint depth
        List<ColumnProfile> dims = profile.dimensions().stream()
                .sorted(Comparator.comparingInt(ColumnProfile::cardinality))
                .limit(blueprint.entityGroupingDepth())
                .collect(Collectors.toList());

        if (dims.isEmpty()) return List.of();

        String entityType = resolveEntityType(dims);

        // Group rows by composite entity key — null keys skipped to avoid NPE in groupingBy
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = entityKey(row, dims);
            if (key != null) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        List<ConstructedEntity> entities = grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= blueprint.minimumSamplePerEntity())
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && !e.getKey().contains("null"))
                .map(e -> buildEntity(e.getKey(), entityType, e.getValue(), profile))
                .filter(Objects::nonNull)
                .limit(MAX_ENTITIES)
                .collect(Collectors.toList());

        // Sort by primary value descending
        entities.sort((a, b) -> Double.compare(
                primaryValue(b.metrics(), profile),
                primaryValue(a.metrics(), profile)));

        return entities;
    }

    // ─── entity construction ─────────────────────────────────────────────

    private ConstructedEntity buildEntity(
            String entityKey,
            String entityType,
            List<Map<String, Object>> rows,
            SchemaProfile profile
    ) {
        Map<String, Double> metrics = new LinkedHashMap<>();

        for (ColumnProfile col : profile.numericColumns()) {
            List<Double> vals = rows.stream()
                    .map(r -> RowAnalytics.toDouble(r.get(col.columnName())))
                    .filter(v -> !Double.isNaN(v))
                    .collect(Collectors.toList());

            if (vals.isEmpty()) continue;

            // Aggregate strategy based on column role
            double agg = switch (col.role()) {
                case VALUE, VOLUME -> RowAnalytics.sum(vals);
                case RATE          -> RowAnalytics.mean(vals);
                case TIME_BUCKET   -> RowAnalytics.mean(vals); // avg time period number
                default            -> RowAnalytics.mean(vals);
            };

            metrics.put(col.columnName(), agg);
        }

        metrics.put("_sample_count", (double) rows.size());

        return new ConstructedEntity(entityKey, entityType, metrics, rows.size());
    }

    // ─── key construction ────────────────────────────────────────────────

    private String entityKey(Map<String, Object> row, List<ColumnProfile> dims) {
        StringJoiner sj = new StringJoiner(" → ");
        for (ColumnProfile dim : dims) {
            Object v = row.get(dim.columnName());
            if (v == null) return null;
            sj.add(v.toString().trim());
        }
        return sj.toString();
    }

    private String resolveEntityType(List<ColumnProfile> dims) {
        if (dims.size() >= 2) return "COMPOSITE";
        String name = dims.get(0).columnName().toLowerCase();
        // Generic type inference without domain knowledge
        if (name.contains("segment") || name.contains("category") || name.contains("class"))
            return "SEGMENT";
        if (name.contains("region") || name.contains("area") || name.contains("zone")
                || name.contains("location") || name.contains("district"))
            return "REGION";
        if (name.contains("product") || name.contains("sku") || name.contains("item"))
            return "PRODUCT";
        if (name.contains("account") || name.contains("customer") || name.contains("client"))
            return "ACCOUNT";
        return "ENTITY";
    }

    private double primaryValue(Map<String, Double> metrics, SchemaProfile profile) {
        ColumnProfile pv = profile.primaryValue();
        if (pv != null && metrics.containsKey(pv.columnName()))
            return metrics.get(pv.columnName());
        return metrics.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
}
