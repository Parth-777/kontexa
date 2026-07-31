package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.schema.NestedSchemaFieldGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds approved-catalogue and schema snapshots for GPT planning.
 */
public final class SemanticCatalogueFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SemanticCatalogueFactory() {}

    public static ApprovedCatalogueSnapshot catalogueFrom(
            JsonNode catalogueNode,
            RegistryResolutionBundle bundle
    ) {
        String tableRef = tableRef(bundle);
        JsonNode table = findTable(catalogueNode, tableRef);
        String qualified = qualifiedName(table, tableRef);

        List<ApprovedCatalogueSnapshot.CatalogueColumn> cols = new ArrayList<>();
        if (table != null && table.path("columns").isArray()) {
            for (JsonNode col : table.path("columns")) {
                String name = col.path("columnName").asText("");
                if (name.isBlank()) continue;
                String role = col.path("role").asText("").toLowerCase(Locale.ROOT);
                String description = firstNonBlank(
                        col.path("businessMeaning").asText(null),
                        col.path("description").asText(null));
                String agg = col.path("aggregationMethod").asText(null);
                if (agg == null || "NONE".equalsIgnoreCase(agg)) {
                    agg = defaultAggFromBundle(bundle, name);
                }
                boolean container = col.path("container").asBoolean(false)
                        || NestedSchemaFieldGraph.ROLE_CONTAINER.equalsIgnoreCase(role)
                        || NestedSchemaFieldGraph.isNestedContainerType(col.path("dataType").asText(""));
                String parentPath = col.path("parentPath").asText(null);
                if (parentPath == null || parentPath.isBlank()) {
                    parentPath = NestedSchemaFieldGraph.parentPath(name);
                }
                cols.add(new ApprovedCatalogueSnapshot.CatalogueColumn(
                        name,
                        role,
                        col.path("dataType").asText("VARCHAR"),
                        description,
                        agg,
                        parseSampleValues(col.path("sampleValues").asText("[]")),
                        container,
                        parentPath));
            }
        }

        if (cols.isEmpty()) {
            cols.addAll(columnsFromBundle(bundle));
        }

        return new ApprovedCatalogueSnapshot(tableRef, qualified, List.copyOf(cols));
    }

    public static SchemaSnapshot schemaFrom(RegistryResolutionBundle bundle) {
        String tableRef = tableRef(bundle);
        List<SchemaSnapshot.SchemaColumn> cols = new ArrayList<>();
        // Use registry keys (catalogue leaf paths), never expressionTemplate SQL like SUM(table.col).
        for (MetricDescriptor m : bundle.metrics()) {
            cols.add(new SchemaSnapshot.SchemaColumn(
                    resolveFieldPath(m.key(), tableRef), m.valueType()));
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            cols.add(new SchemaSnapshot.SchemaColumn(
                    resolveFieldPath(d.key() != null ? d.key() : d.expression(), tableRef),
                    mapDimensionType(d.type())));
        }
        return new SchemaSnapshot(tableRef, List.copyOf(cols));
    }

    private static List<ApprovedCatalogueSnapshot.CatalogueColumn> columnsFromBundle(
            RegistryResolutionBundle bundle
    ) {
        String tableRef = tableRef(bundle);
        List<ApprovedCatalogueSnapshot.CatalogueColumn> cols = new ArrayList<>();
        for (MetricDescriptor m : bundle.metrics()) {
            String col = resolveFieldPath(m.key(), tableRef);
            cols.add(new ApprovedCatalogueSnapshot.CatalogueColumn(
                    col, "metric", m.valueType(), null, m.aggregation(), List.of()));
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            String col = resolveFieldPath(d.key() != null ? d.key() : d.expression(), tableRef);
            cols.add(new ApprovedCatalogueSnapshot.CatalogueColumn(
                    col, mapRole(d.type()), mapDimensionType(d.type()), null, null, List.of()));
        }
        return cols;
    }

    private static JsonNode findTable(JsonNode catalogueNode, String tableRef) {
        if (catalogueNode == null || tableRef == null) return null;
        for (JsonNode table : catalogueNode.path("tables")) {
            String name = table.path("tableName").asText("");
            if (tableRef.equalsIgnoreCase(name)) return table;
        }
        if (catalogueNode.path("tables").isArray() && !catalogueNode.path("tables").isEmpty()) {
            return catalogueNode.path("tables").get(0);
        }
        return null;
    }

    private static String qualifiedName(JsonNode table, String fallback) {
        if (table == null) return fallback;
        String schema = table.path("tableSchema").asText("public");
        String name = table.path("tableName").asText(fallback);
        return schema + "." + name;
    }

    private static String tableRef(RegistryResolutionBundle bundle) {
        if (bundle.entities() != null && !bundle.entities().isEmpty()) {
            return bundle.entities().getFirst().tableRef();
        }
        if (bundle.metrics() != null && !bundle.metrics().isEmpty()) {
            String key = bundle.metrics().getFirst().key();
            if (key != null && key.contains(".")) {
                return key.substring(0, key.indexOf('.'));
            }
        }
        return "unknown";
    }

    /**
     * Strips a leading {@code tableRef.} prefix when present; preserves nested field paths.
     */
    static String resolveFieldPath(String expression, String tableRef) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        String trimmed = expression.trim();
        if (tableRef != null && !tableRef.isBlank()) {
            String prefix = tableRef + ".";
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return trimmed.substring(prefix.length());
            }
        }
        int dot = trimmed.indexOf('.');
        if (dot > 0 && tableRef != null && !tableRef.isBlank()
                && trimmed.substring(0, dot).equalsIgnoreCase(tableRef)) {
            return trimmed.substring(dot + 1);
        }
        return trimmed;
    }

    private static String defaultAggFromBundle(RegistryResolutionBundle bundle, String column) {
        String tableRef = tableRef(bundle);
        return bundle.metrics().stream()
                .filter(m -> resolveFieldPath(m.key(), tableRef).equalsIgnoreCase(column))
                .map(MetricDescriptor::aggregation)
                .findFirst()
                .orElse("SUM");
    }

    private static String mapRole(String dimensionType) {
        if (dimensionType == null) return "dimension";
        return switch (dimensionType.toUpperCase(Locale.ROOT)) {
            case "TEMPORAL", "TIMESTAMP" -> "timestamp";
            default -> "dimension";
        };
    }

    private static String mapDimensionType(String dimensionType) {
        if (dimensionType == null) return "VARCHAR";
        return switch (dimensionType.toUpperCase(Locale.ROOT)) {
            case "TEMPORAL" -> "TIMESTAMP";
            case "NUMERIC" -> "FLOAT";
            default -> "VARCHAR";
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return "";
    }

    private static List<String> parseSampleValues(String raw) {
        try {
            JsonNode arr = MAPPER.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) out.add(n.asText());
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }
}
