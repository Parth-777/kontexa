package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamically constructs business entities from raw row data.
 *
 * The engine inspects column names to discover entity dimensions
 * (zones, routes, segments, cohorts, etc.) and groups rows accordingly.
 *
 * Entity types detected:
 *   ROUTE      — composite of pickup + dropoff identifiers (PU_zone, DO_zone)
 *   ZONE       — single geographic identifier (zone_id, location_id, pickup_location)
 *   SEGMENT    — business category (segment, category, type, class)
 *   COHORT     — temporal or behavioral group (hour, day_of_week, payment_type)
 *   PRODUCT    — product/service identifier (product_id, service_type)
 *   GENERIC    — any other detected grouping dimension
 *
 * For each constructed entity, all numeric columns are aggregated (sum for
 * value columns, average for rate columns, count for volume columns).
 */
@Component
public class EntityConstructionEngine {

    private static final int MIN_ROWS_PER_ENTITY = 2;
    private static final int MAX_ENTITIES = 50;

    // ─── entity type detection patterns ─────────────────────────────────

    private static final String[] PICKUP_PATTERNS   = {"pickup_location", "pu_location", "pu_zone", "pickup_zone", "origin"};
    private static final String[] DROPOFF_PATTERNS  = {"dropoff_location", "do_location", "do_zone", "dropoff_zone", "destination"};
    private static final String[] ZONE_PATTERNS     = {"zone_id", "zone", "location_id", "location", "area", "district", "region"};
    private static final String[] SEGMENT_PATTERNS  = {"segment", "category", "type", "class", "group", "tier", "service"};
    private static final String[] COHORT_PATTERNS   = {"hour", "day_of_week", "weekday", "period", "payment_type", "vendor"};
    private static final String[] PRODUCT_PATTERNS  = {"product_id", "product", "service_id", "route_id", "corridor"};

    // ─── public API ──────────────────────────────────────────────────────

    public List<ConstructedEntity> construct(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < MIN_ROWS_PER_ENTITY * 2) return List.of();

        Set<String> cols = RowAnalytics.columns(rows);

        // Try route construction first (most specific)
        String pickupCol  = findColumn(cols, PICKUP_PATTERNS);
        String dropoffCol = findColumn(cols, DROPOFF_PATTERNS);
        if (pickupCol != null && dropoffCol != null) {
            return constructByDimension(rows, pickupCol, dropoffCol, "ROUTE");
        }

        // Single-dimension entity types
        String zoneCol    = findColumn(cols, ZONE_PATTERNS);
        if (zoneCol != null) return constructByDimension(rows, zoneCol, null, "ZONE");

        String segmentCol = findColumn(cols, SEGMENT_PATTERNS);
        if (segmentCol != null) return constructByDimension(rows, segmentCol, null, "SEGMENT");

        String productCol = findColumn(cols, PRODUCT_PATTERNS);
        if (productCol != null) return constructByDimension(rows, productCol, null, "PRODUCT");

        String cohortCol  = findColumn(cols, COHORT_PATTERNS);
        if (cohortCol != null) return constructByDimension(rows, cohortCol, null, "COHORT");

        // Fallback: find any non-numeric column as grouping dimension
        String fallback = cols.stream()
                .filter(c -> !RowAnalytics.isNumeric(rows.get(0).get(c)))
                .findFirst().orElse(null);
        if (fallback != null) return constructByDimension(rows, fallback, null, "GENERIC");

        return List.of();
    }

    // ─── construction logic ──────────────────────────────────────────────

    private List<ConstructedEntity> constructByDimension(
            List<Map<String, Object>> rows,
            String dim1,
            String dim2,
            String entityType
    ) {
        List<String> numericCols = RowAnalytics.numericColumns(rows);
        List<String> valueCols   = RowAnalytics.valueColumns(rows);
        List<String> volumeCols  = RowAnalytics.volumeColumns(rows);

        // Group rows by entity key
        Map<String, List<Map<String, Object>>> grouped = rows.stream()
                .collect(Collectors.groupingBy(r -> entityKey(r, dim1, dim2)));

        return grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= MIN_ROWS_PER_ENTITY)
                .map(e -> buildEntity(e.getKey(), entityType, e.getValue(),
                        numericCols, valueCols, volumeCols))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(en ->
                        -en.metrics().getOrDefault("total_value", en.metrics().getOrDefault("value", 0.0))))
                .limit(MAX_ENTITIES)
                .collect(Collectors.toList());
    }

    private ConstructedEntity buildEntity(
            String entityKey, String entityType,
            List<Map<String, Object>> rows,
            List<String> numericCols,
            List<String> valueCols,
            List<String> volumeCols
    ) {
        if (entityKey == null || entityKey.isBlank()) return null;

        Map<String, Double> metrics = new LinkedHashMap<>();

        for (String col : numericCols) {
            List<Double> vals = rows.stream()
                    .map(r -> RowAnalytics.toDouble(r.get(col)))
                    .filter(v -> !Double.isNaN(v))
                    .collect(Collectors.toList());
            if (vals.isEmpty()) continue;

            // Sum value columns; average rate/duration columns
            boolean isValue  = valueCols.contains(col);
            boolean isVolume = volumeCols.contains(col);
            double  agg      = isValue || isVolume
                    ? RowAnalytics.sum(vals)
                    : RowAnalytics.mean(vals);

            metrics.put(normaliseKey(col), agg);
        }

        // Always add sample count
        metrics.put("sample_count", (double) rows.size());

        return new ConstructedEntity(entityKey, entityType, metrics, rows.size());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private String entityKey(Map<String, Object> row, String dim1, String dim2) {
        Object v1 = row.get(dim1);
        if (v1 == null) return null;
        if (dim2 == null) return v1.toString();
        Object v2 = row.get(dim2);
        return v2 == null ? v1.toString() : v1.toString() + " → " + v2.toString();
    }

    private String findColumn(Set<String> cols, String[] patterns) {
        for (String p : patterns) {
            for (String c : cols) {
                if (c.toLowerCase().contains(p)) return c;
            }
        }
        return null;
    }

    private String normaliseKey(String col) {
        return col.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}
