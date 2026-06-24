package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders warehouse SQL exclusively from a {@link CanonicalQueryModel}.
 * No AnalysisPlan, intent routing, template delegation, defaults, or inference.
 */
@Component
public class CanonicalSqlRenderer {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    public QuerySpec render(CanonicalQueryModel model, String tableRef) {
        if (model == null) {
            throw new IllegalArgumentException("canonical model required");
        }
        if (tableRef == null || tableRef.isBlank()) {
            throw new IllegalArgumentException("table reference required");
        }

        String sql;
        if (isBivariate(model)) {
            sql = renderBivariate(model, tableRef);
        } else if (hasPartition(model)) {
            sql = renderGrouped(model, tableRef);
        } else {
            sql = renderScalar(model, tableRef);
        }

        return new QuerySpec("canonical__sql", sql, Map.of(
                "renderer", "CanonicalSqlRenderer",
                "table", tableRef));
    }

    private static boolean isBivariate(CanonicalQueryModel model) {
        return model.bivariate() != null
                && model.bivariate().function() != null
                && !model.bivariate().function().isBlank();
    }

    private static boolean hasPartition(CanonicalQueryModel model) {
        return model.partition() != null
                && model.partition().column() != null
                && !model.partition().column().isBlank();
    }

    private String renderBivariate(CanonicalQueryModel model, String tableRef) {
        if (hasPartition(model)) {
            return renderGroupedBivariate(model, tableRef);
        }
        CanonicalQueryModel.BivariateSpec b = model.bivariate();
        String colA = requireIdentifier(b.columnA(), "bivariate.columnA");
        String colB = requireIdentifier(b.columnB(), "bivariate.columnB");
        String function = b.function().toUpperCase(Locale.ROOT);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n  ");
        sql.append(bivariateExpression(function, colA, colB));
        sql.append("\nFROM ").append(tableRef);
        appendWhere(sql, model, List.of(
                colA + " IS NOT NULL",
                colB + " IS NOT NULL"));
        appendOrderBy(sql, model);
        appendLimit(sql, model);
        return sql.toString();
    }

    private String renderGroupedBivariate(CanonicalQueryModel model, String tableRef) {
        CanonicalQueryModel.BivariateSpec b = model.bivariate();
        String colA = requireIdentifier(b.columnA(), "bivariate.columnA");
        String colB = requireIdentifier(b.columnB(), "bivariate.columnB");
        String function = b.function().toUpperCase(Locale.ROOT);

        PartitionExpression partition = partitionExpression(model);
        List<String> selectParts = new ArrayList<>();
        selectParts.add(partition.expression() + " AS " + partition.alias());
        selectParts.add(bivariateExpression(function, colA, colB));

        if (model.ratio() != null && model.ratio().denominator() != null
                && model.ratio().denominator().column() != null
                && !model.ratio().denominator().column().isBlank()) {
            CanonicalQueryModel.MeasureSpec denom = model.ratio().denominator();
            String denomCol = requireIdentifier(denom.column(), "ratio.denominator.column");
            String denomAgg = requireAggregation(denom.aggregation());
            selectParts.add(denomAgg + "(" + denomCol + ") AS " + denomCol);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n  ").append(String.join(",\n  ", selectParts));
        sql.append("\nFROM ").append(tableRef);
        appendWhere(sql, model, List.of(
                colA + " IS NOT NULL",
                colB + " IS NOT NULL"));
        sql.append("\nGROUP BY ").append(partition.expression());
        appendOrderBy(sql, model);
        appendLimit(sql, model);
        return sql.toString();
    }

    private static String bivariateExpression(String function, String colA, String colB) {
        if ("CORR".equals(function)) {
            return "CORR(" + colA + ", " + colB + ") AS correlation_coefficient";
        }
        throw new IllegalArgumentException("unsupported bivariate.function: " + function);
    }

    private String renderGrouped(CanonicalQueryModel model, String tableRef) {
        CanonicalQueryModel.MeasureSpec measure = model.measure();
        String metricCol = requireIdentifier(measure.column(), "measure.column");
        String agg = requireAggregation(measure.aggregation());

        PartitionExpression partition = partitionExpression(model);
        List<String> selectParts = new ArrayList<>();
        selectParts.add(partition.expression() + " AS " + partition.alias());
        selectParts.add(agg + "(" + metricCol + ") AS " + metricCol);

        if (model.ratio() != null && model.ratio().denominator() != null
                && model.ratio().denominator().column() != null
                && !model.ratio().denominator().column().isBlank()) {
            CanonicalQueryModel.MeasureSpec denom = model.ratio().denominator();
            String denomCol = requireIdentifier(denom.column(), "ratio.denominator.column");
            String denomAgg = requireAggregation(denom.aggregation());
            selectParts.add(denomAgg + "(" + denomCol + ") AS " + denomCol);
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n  ").append(String.join(",\n  ", selectParts));
        sql.append("\nFROM ").append(tableRef);
        appendWhere(sql, model, List.of());
        sql.append("\nGROUP BY ").append(partition.expression());
        appendOrderBy(sql, model);
        appendLimit(sql, model);
        return sql.toString();
    }

    private String renderScalar(CanonicalQueryModel model, String tableRef) {
        CanonicalQueryModel.MeasureSpec measure = model.measure();
        String metricCol = requireIdentifier(measure.column(), "measure.column");
        String agg = requireAggregation(measure.aggregation());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT\n  ").append(agg).append("(").append(metricCol).append(") AS ")
                .append(metricCol);
        sql.append("\nFROM ").append(tableRef);
        appendWhere(sql, model, List.of());
        appendOrderBy(sql, model);
        appendLimit(sql, model);
        return sql.toString();
    }

    private static PartitionExpression partitionExpression(CanonicalQueryModel model) {
        String column = requireIdentifier(model.partition().column(), "partition.column");
        String grain = model.partition().timeGrain();
        if (grain != null && !grain.isBlank()) {
            String upperGrain = grain.toUpperCase(Locale.ROOT);
            String alias = column + "_" + upperGrain.toLowerCase(Locale.ROOT);
            return new PartitionExpression(
                    "DATE_TRUNC('" + upperGrain + "', " + column + ")",
                    alias);
        }
        return new PartitionExpression(column, column);
    }

    private static void appendWhere(
            StringBuilder sql,
            CanonicalQueryModel model,
            List<String> extraPredicates
    ) {
        List<String> predicates = new ArrayList<>();
        if (extraPredicates != null) {
            predicates.addAll(extraPredicates);
        }
        if (model.filters() != null) {
            for (CanonicalQueryModel.FilterSpec filter : model.filters()) {
                String col = requireIdentifier(filter.column(), "filter.column");
                String op = filter.operator().toUpperCase(Locale.ROOT);
                String value = formatFilterValue(filter.value(), op);
                predicates.add(col + " " + op + " " + value);
            }
        }
        if (!predicates.isEmpty()) {
            sql.append("\nWHERE ").append(String.join("\n  AND ", predicates));
        }
    }

    private static void appendOrderBy(StringBuilder sql, CanonicalQueryModel model) {
        if (model.ordering() == null || model.ordering().column() == null
                || model.ordering().column().isBlank()) {
            return;
        }
        String column = requireIdentifier(model.ordering().column(), "ordering.column");
        String direction = model.ordering().direction() != null
                ? model.ordering().direction().toUpperCase(Locale.ROOT)
                : null;
        if (direction != null && !direction.isBlank()) {
            sql.append("\nORDER BY ").append(column).append(" ").append(direction);
        } else {
            sql.append("\nORDER BY ").append(column);
        }
    }

    private static void appendLimit(StringBuilder sql, CanonicalQueryModel model) {
        if (model.limit() != null) {
            sql.append("\nLIMIT ").append(model.limit());
        }
    }

    private static String formatFilterValue(String raw, String operator) {
        if (raw == null) {
            throw new IllegalArgumentException("filter.value required");
        }
        if ("IN".equals(operator)) {
            return "(" + raw + ")";
        }
        if (raw.startsWith("'") || raw.matches("-?\\d+(\\.\\d+)?")) {
            return raw;
        }
        return "'" + raw.replace("'", "''") + "'";
    }

    private static String requireAggregation(String aggregation) {
        if (aggregation == null || aggregation.isBlank()) {
            throw new IllegalArgumentException("measure.aggregation required");
        }
        return aggregation.toUpperCase(Locale.ROOT);
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " required");
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("unsafe identifier for " + field + ": " + value);
        }
        return value;
    }

    private record PartitionExpression(String expression, String alias) {}
}
