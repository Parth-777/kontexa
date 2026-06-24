package com.example.BACKEND.catalogue.decision.execution.semantic;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps physical column references from the resolved registry into semantic roles.
 *
 * Semantic roles drive SQL generation — a column's role determines whether it
 * appears in GROUP BY, SUM(), EXTRACT(), a window function, etc.
 *
 * Classification uses THREE signals (in priority order):
 *
 *   1. Registry-declared type (DimensionDescriptor.type = TEMPORAL → TIME_DIMENSION)
 *   2. Generic lexical tokens in the column name (domain-agnostic terms only)
 *   3. Data type from MetricDescriptor.valueType (INT/FLOAT → numeric roles)
 *
 * No business-domain names are hardcoded. Terms like "hour", "date", "amount",
 * "count", "rate" are universal analytical vocabulary, not domain assumptions.
 */
@Component
public class SchemaSemanticResolver {

    public enum SemanticRole {
        TIME_DIMENSION,    // datetime/timestamp — can derive hour/day/month
        VALUE_METRIC,      // high-magnitude numeric output (revenue, cost, amount)
        VOLUME_METRIC,     // count/quantity — integer-dominant
        ENTITY_DIMENSION,  // low-cardinality categorical — ideal GROUP BY
        RATE_METRIC,       // per-unit continuous (distance, duration, latency)
        IDENTIFIER,        // high-cardinality key — skip for grouping
        UNKNOWN
    }

    public record ResolvedColumn(
            String       columnName,
            String       tableRef,
            SemanticRole role,
            String       aggregationHint  // SUM / AVG / COUNT / NONE
    ) {}

    public record ResolvedSchema(
            String              tableRef,
            List<ResolvedColumn> columns
    ) {
        public List<ResolvedColumn> byRole(SemanticRole role) {
            return columns.stream().filter(c -> c.role() == role).collect(Collectors.toList());
        }
        public Optional<ResolvedColumn> firstByRole(SemanticRole role) {
            return byRole(role).stream().findFirst();
        }
        public boolean has(SemanticRole role) { return !byRole(role).isEmpty(); }
    }

    // ─── generic lexical token sets (domain-agnostic) ─────────────────────

    private static final Set<String> TIME_TOKENS = Set.of(
            "time", "date", "datetime", "timestamp", "ts", "at",
            "hour", "day", "week", "month", "year", "quarter", "period",
            "created", "updated", "occurred", "recorded", "scheduled"
    );
    private static final Set<String> VALUE_TOKENS = Set.of(
            "amount", "value", "total", "sum", "cost", "price",
            "spend", "income", "output", "revenue", "metric", "measure"
    );
    private static final Set<String> VOLUME_TOKENS = Set.of(
            "count", "cnt", "num", "qty", "quantity",
            "events", "records", "orders", "units", "items",
            "trips", "sessions", "visits", "ops", "occurrences"
    );
    private static final Set<String> RATE_TOKENS = Set.of(
            "distance", "duration", "latency", "speed", "rate",
            "length", "time_taken", "elapsed", "miles", "km",
            "minutes", "seconds", "hours_elapsed"
    );
    private static final Set<String> ID_TOKENS = Set.of(
            "id", "uuid", "key", "hash", "token", "ref", "code",
            "identifier", "pk", "guid"
    );

    // ─── public API ──────────────────────────────────────────────────────

    public ResolvedSchema resolve(RegistryResolutionBundle bundle, String tableRef) {
        List<ResolvedColumn> cols = new ArrayList<>();

        // Resolve dimension columns
        for (DimensionDescriptor dim : bundle.dimensions()) {
            if (!dim.expression().startsWith(tableRef)) continue;
            String colName = extractColumn(dim.expression());
            cols.add(new ResolvedColumn(colName, tableRef,
                    resolveFromDimType(dim.type(), colName), "NONE"));
        }

        // Resolve metric columns
        for (MetricDescriptor metric : bundle.metrics()) {
            if (!metric.key().startsWith(tableRef)) continue;
            String colName = extractColumn(metric.key());
            // Skip if already resolved as a dimension
            if (cols.stream().anyMatch(c -> c.columnName().equals(colName))) continue;
            SemanticRole role = resolveFromMetric(metric, colName);
            cols.add(new ResolvedColumn(colName, tableRef, role,
                    role == SemanticRole.VOLUME_METRIC ? "SUM" :
                    role == SemanticRole.VALUE_METRIC  ? "SUM" :
                    role == SemanticRole.RATE_METRIC   ? "AVG" : "NONE"));
        }

        // If still empty, synthesise columns from entity grainKeys (last resort)
        if (cols.isEmpty()) {
            EntityDescriptor entity = bundle.entities().stream()
                    .filter(e -> tableRef.contains(e.tableRef()))
                    .findFirst().orElse(null);
            if (entity != null) {
                for (String gk : entity.grainKeys()) {
                    cols.add(new ResolvedColumn(gk, tableRef,
                            classifyByName(gk), "NONE"));
                }
            }
        }

        return new ResolvedSchema(tableRef, cols);
    }

    // ─── classification logic ─────────────────────────────────────────────

    private SemanticRole resolveFromDimType(String registryType, String colName) {
        if (registryType == null) return classifyByName(colName);
        return switch (registryType.toUpperCase()) {
            case "TEMPORAL"    -> SemanticRole.TIME_DIMENSION;
            case "CATEGORICAL" -> SemanticRole.ENTITY_DIMENSION;
            case "NUMERIC"     -> SemanticRole.RATE_METRIC;
            default            -> classifyByName(colName);
        };
    }

    private SemanticRole resolveFromMetric(MetricDescriptor metric, String colName) {
        String vt = metric.valueType() == null ? "" : metric.valueType().toUpperCase();
        if (vt.contains("INT") || vt.contains("BIGINT")) {
            if (containsAny(colName, VOLUME_TOKENS)) return SemanticRole.VOLUME_METRIC;
            if (containsAny(colName, VALUE_TOKENS))  return SemanticRole.VALUE_METRIC;
            return SemanticRole.VOLUME_METRIC;
        }
        if (vt.contains("FLOAT") || vt.contains("DOUBLE") || vt.contains("DECIMAL")
                || vt.contains("NUMERIC")) {
            if (containsAny(colName, VALUE_TOKENS)) return SemanticRole.VALUE_METRIC;
            if (containsAny(colName, RATE_TOKENS))  return SemanticRole.RATE_METRIC;
            return SemanticRole.VALUE_METRIC;
        }
        if (vt.contains("TIMESTAMP") || vt.contains("DATE") || vt.contains("DATETIME")) {
            return SemanticRole.TIME_DIMENSION;
        }
        return classifyByName(colName);
    }

    SemanticRole classifyByName(String col) {
        String lower = col.toLowerCase();
        if (containsAny(lower, ID_TOKENS)     && !containsAny(lower, TIME_TOKENS))
            return SemanticRole.IDENTIFIER;
        if (containsAny(lower, TIME_TOKENS))   return SemanticRole.TIME_DIMENSION;
        if (containsAny(lower, VALUE_TOKENS))  return SemanticRole.VALUE_METRIC;
        if (containsAny(lower, VOLUME_TOKENS)) return SemanticRole.VOLUME_METRIC;
        if (containsAny(lower, RATE_TOKENS))   return SemanticRole.RATE_METRIC;
        // If it looks like a categorical string column (ends with _id, _type, _name etc.)
        if (lower.endsWith("_id") || lower.endsWith("_type") || lower.endsWith("_name")
                || lower.endsWith("_flag") || lower.endsWith("_category")
                || lower.endsWith("_status") || lower.endsWith("_code"))
            return SemanticRole.ENTITY_DIMENSION;
        return SemanticRole.UNKNOWN;
    }

    private String extractColumn(String dotExpression) {
        int dot = dotExpression.lastIndexOf('.');
        return dot >= 0 ? dotExpression.substring(dot + 1) : dotExpression;
    }

    private boolean containsAny(String lower, Set<String> tokens) {
        for (String t : tokens) {
            if (lower.contains(t)) return true;
        }
        return false;
    }
}
