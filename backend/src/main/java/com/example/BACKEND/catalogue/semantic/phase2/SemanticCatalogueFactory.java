package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
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
                cols.add(new ApprovedCatalogueSnapshot.CatalogueColumn(
                        name,
                        role,
                        col.path("dataType").asText("VARCHAR"),
                        description,
                        agg,
                        parseSampleValues(col.path("sampleValues").asText("[]"))));
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
        for (MetricDescriptor m : bundle.metrics()) {
            cols.add(new SchemaSnapshot.SchemaColumn(
                    bareColumn(m.expressionTemplate()), m.valueType()));
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            cols.add(new SchemaSnapshot.SchemaColumn(
                    bareColumn(d.expression()), mapDimensionType(d.type())));
        }
        return new SchemaSnapshot(tableRef, List.copyOf(cols));
    }

    private static List<ApprovedCatalogueSnapshot.CatalogueColumn> columnsFromBundle(
            RegistryResolutionBundle bundle
    ) {
        List<ApprovedCatalogueSnapshot.CatalogueColumn> cols = new ArrayList<>();
        for (MetricDescriptor m : bundle.metrics()) {
            String col = bareColumn(m.expressionTemplate());
            cols.add(new ApprovedCatalogueSnapshot.CatalogueColumn(
                    col, "metric", m.valueType(), null, m.aggregation(), List.of()));
        }
        for (DimensionDescriptor d : bundle.dimensions()) {
            String col = bareColumn(d.expression());
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
            return bareColumn(bundle.metrics().getFirst().expressionTemplate());
        }
        return "unknown";
    }

    private static String bareColumn(String expression) {
        if (expression == null) return "";
        int dot = expression.lastIndexOf('.');
        return dot >= 0 ? expression.substring(dot + 1) : expression;
    }

    private static String defaultAggFromBundle(RegistryResolutionBundle bundle, String column) {
        return bundle.metrics().stream()
                .filter(m -> bareColumn(m.expressionTemplate()).equalsIgnoreCase(column))
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
