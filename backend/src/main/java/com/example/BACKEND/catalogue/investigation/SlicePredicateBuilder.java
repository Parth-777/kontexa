package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds warehouse SQL predicates from composable slice constraints.
 * Contrast-agnostic: time, filter, rank, and population bounds use the same path.
 */
@Component
public class SlicePredicateBuilder {

    public BuiltSlice build(InvestigationObjective.Slice slice,
                            List<InvestigationObjective.Filter> scopeFilters,
                            String tableRef,
                            InvestigationObjective.Measure measure) {
        List<String> clauses = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();

        applyScope(clauses, scopeFilters, columns);

        if (slice == null || slice.constraints() == null || slice.constraints().isEmpty()) {
            labels.add("scoped population");
            return new BuiltSlice(
                    clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses),
                    String.join(" ∧ ", labels),
                    Set.copyOf(columns));
        }

        List<String> sliceClauses = new ArrayList<>();
        for (InvestigationObjective.Constraint c : slice.constraints()) {
            if (c == null) {
                continue;
            }
            if (Boolean.TRUE.equals(c.population())) {
                labels.add("population");
                continue;
            }
            if (c.filter() != null) {
                InvestigationObjective.Filter f = c.filter();
                sliceClauses.add(filterSql(f));
                labels.add(f.column() + " " + f.operator() + " " + f.value());
                columns.add(f.column());
            }
            if (c.timeRange() != null) {
                InvestigationObjective.TimeRange t = c.timeRange();
                sliceClauses.add("(" + sqlRef(t.column()) + " >= '" + esc(t.start())
                        + "' AND " + sqlRef(t.column()) + " < '" + esc(t.end()) + "')");
                labels.add(t.column() + " in [" + t.start() + ", " + t.end() + ")");
                columns.add(t.column());
            }
            if (c.rank() != null) {
                InvestigationObjective.Rank r = c.rank();
                sliceClauses.add(rankSql(r, scopeFilters, tableRef, measure));
                labels.add("rank " + r.side() + " " + (int) (r.fraction() * 100) + "% on " + r.column());
                columns.add(r.column());
                if (r.grain() != null) {
                    columns.addAll(r.grain());
                }
            }
        }

        if (!sliceClauses.isEmpty()) {
            clauses.add("(" + String.join(" AND ", sliceClauses) + ")");
        }
        String sql = clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses);
        String label = labels.isEmpty() ? "slice" : String.join(" ∧ ", labels);
        return new BuiltSlice(sql, label, Set.copyOf(columns));
    }

    private void applyScope(List<String> clauses,
                            List<InvestigationObjective.Filter> scopeFilters,
                            Set<String> columns) {
        if (scopeFilters == null) {
            return;
        }
        for (InvestigationObjective.Filter f : scopeFilters) {
            clauses.add(filterSql(f));
            columns.add(f.column());
        }
    }

    private String rankSql(InvestigationObjective.Rank rank,
                           List<InvestigationObjective.Filter> scopeFilters,
                           String tableRef,
                           InvestigationObjective.Measure measure) {
        boolean entityRank = rank.grain() != null && !rank.grain().isEmpty();
        List<String> grain = entityRank ? rank.grain() : List.of(rank.column());
        String scopeWhere = scopeWhere(scopeFilters);
        String rankMetricColumn = entityRank ? rank.column() : rank.column();
        InvestigationObjective.Measure rankMeasure = entityRank
                ? new InvestigationObjective.Measure(rankMetricColumn, measure.aggregation(), grain)
                : measure;
        String aggExpr = aggExpr(rankMeasure);
        String orderDir = "UPPER".equalsIgnoreCase(rank.side()) ? "DESC" : "ASC";
        int bucket = Math.max(1, (int) Math.round(rank.fraction() * 100));
        int limit = Math.max(1, bucket);

        if (grain.size() == 1 && !entityRank) {
            String grainCol = sqlRef(grain.get(0));
            return grainCol + " IN (SELECT " + grainCol + " FROM ("
                    + "SELECT " + grainCol + ", " + aggExpr + " AS _rank_metric "
                    + "FROM " + tableRef + scopeWhere
                    + " GROUP BY " + grainCol
                    + " ORDER BY _rank_metric " + orderDir
                    + " LIMIT " + limit
                    + ") AS _ranked)";
        }

        String grainCols = grain.stream().map(SlicePredicateBuilder::sqlRef).collect(Collectors.joining(", "));
        return "(" + grainCols + ") IN (SELECT " + grainCols + " FROM ("
                + "SELECT " + grainCols + ", " + aggExpr + " AS _rank_metric "
                + "FROM " + tableRef + scopeWhere
                + " GROUP BY " + grainCols
                + " ORDER BY _rank_metric " + orderDir
                + " LIMIT " + limit
                + ") AS _ranked)";
    }

    private String aggExpr(InvestigationObjective.Measure measure) {
        if (InvestigationObjective.AGG_COUNT.equals(measure.aggregation())) {
            return "COUNT(*)";
        }
        if (InvestigationObjective.AGG_AVG.equals(measure.aggregation())) {
            return "AVG(" + sqlRef(measure.column()) + ")";
        }
        return "SUM(" + sqlRef(measure.column()) + ")";
    }

    private String scopeWhere(List<InvestigationObjective.Filter> scopeFilters) {
        if (scopeFilters == null || scopeFilters.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>();
        for (InvestigationObjective.Filter f : scopeFilters) {
            clauses.add(filterSql(f));
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    private String filterSql(InvestigationObjective.Filter f) {
        String column = sqlRef(f.column());
        String op = f.operator() == null ? "=" : f.operator().trim();
        if ("IS NULL".equalsIgnoreCase(op) || "IS NOT NULL".equalsIgnoreCase(op)) {
            return column + " " + op.toUpperCase();
        }
        return column + " " + op + " " + literal(f.value());
    }

    private String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        if (value.matches("-?\\d+(\\.\\d+)?")
                || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return value;
        }
        return "'" + esc(value) + "'";
    }

    private static String sqlRef(String fieldPath) {
        return SqlFieldReferenceEmitter.emitFieldReference(fieldPath);
    }

    private String esc(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    public record BuiltSlice(String sqlPredicate, String label, Set<String> columns) {}
}
