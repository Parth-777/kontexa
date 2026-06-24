package com.example.BACKEND.experiment.phase1;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds catalogue + schema snapshots from bundle + approved descriptions.
 */
public final class Phase1CatalogueFactory {

    private Phase1CatalogueFactory() {}

    public static Phase1CatalogueSnapshot catalogueFrom(
            String datasetId,
            RegistryResolutionBundle bundle,
            Phase1CataloguePayloadMode mode
    ) {
        String table = tableRef(bundle);
        List<Phase1CatalogueEntry> entries = new ArrayList<>();
        for (Phase1ApprovedCatalogue.ColumnDef def : Phase1ApprovedCatalogue.columnsFor(datasetId)) {
            String agg = bundle.metrics().stream()
                    .filter(m -> m.expressionTemplate().equalsIgnoreCase(def.column()))
                    .map(m -> m.aggregation())
                    .findFirst()
                    .orElse(null);
            String dataType = dataTypeFor(def);
            String description = mode == Phase1CataloguePayloadMode.WITH_DESCRIPTIONS
                    ? def.description() : null;
            entries.add(new Phase1CatalogueEntry(
                    def.column(),
                    humanize(def.column()),
                    def.type(),
                    dataType,
                    agg,
                    description));
        }
        return new Phase1CatalogueSnapshot(table, List.copyOf(entries), mode);
    }

    public static Phase1SchemaSnapshot schemaFrom(RegistryResolutionBundle bundle) {
        String table = tableRef(bundle);
        List<Phase1SchemaSnapshot.Phase1SchemaColumn> cols = new ArrayList<>();
        cols.add(new Phase1SchemaSnapshot.Phase1SchemaColumn("id", "VARCHAR"));
        for (var m : bundle.metrics()) {
            cols.add(new Phase1SchemaSnapshot.Phase1SchemaColumn(
                    m.expressionTemplate(), m.valueType()));
        }
        for (var d : bundle.dimensions()) {
            String col = columnKey(d.expression());
            cols.add(new Phase1SchemaSnapshot.Phase1SchemaColumn(col, dimensionType(d.type())));
        }
        return new Phase1SchemaSnapshot(table, List.copyOf(cols));
    }

    private static String tableRef(RegistryResolutionBundle bundle) {
        if (bundle.entities() != null && !bundle.entities().isEmpty()) {
            return bundle.entities().get(0).tableRef();
        }
        if (bundle.metrics() != null && !bundle.metrics().isEmpty()) {
            return bundle.metrics().get(0).key().split("\\.")[0];
        }
        return "unknown";
    }

    private static String columnKey(String expression) {
        if (expression == null) return "";
        int dot = expression.lastIndexOf('.');
        return dot >= 0 ? expression.substring(dot + 1) : expression;
    }

    private static String humanize(String column) {
        if (column == null) return "";
        return column.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String dataTypeFor(Phase1ApprovedCatalogue.ColumnDef def) {
        if ("metric".equals(def.type())) return "FLOAT";
        String col = def.column().toLowerCase(Locale.ROOT);
        if (col.contains("week") || col.contains("hour") || col.contains("month") || col.endsWith("_at")) {
            return "TIMESTAMP";
        }
        return "VARCHAR";
    }

    private static String dimensionType(String type) {
        return switch (type != null ? type.toUpperCase(Locale.ROOT) : "") {
            case "TEMPORAL" -> "TIMESTAMP";
            case "NUMERIC" -> "FLOAT";
            default -> "VARCHAR";
        };
    }
}
