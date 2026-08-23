package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.transforms.BigQueryDialect;
import com.example.BACKEND.catalogue.decision.transforms.WarehouseDialect;
import com.example.BACKEND.catalogue.schema.SqlFieldReferenceEmitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles evidence requests into deterministic SQL query specs.
 * {@code kind} selects a warehouse retrieval template only — never a runtime path.
 * Unknown kinds fail closed (no silent fallback).
 */
@Component
public class EvidenceRequestSqlCompiler {

    public static final Set<String> SUPPORTED_KINDS = Set.of(
            "slice_metric",
            "metric_by_dimension",
            "distribution",
            "correlation",
            "rank",
            "top_n",
            "bottom_n",
            "share",
            "concentration",
            "time_series"
    );

    private final SlicePredicateBuilder predicateBuilder;
    private final WarehouseDialect dialect;

    public EvidenceRequestSqlCompiler(SlicePredicateBuilder predicateBuilder) {
        this(predicateBuilder, new BigQueryDialect());
    }

    @Autowired
    public EvidenceRequestSqlCompiler(SlicePredicateBuilder predicateBuilder, WarehouseDialect dialect) {
        this.predicateBuilder = predicateBuilder;
        this.dialect = dialect != null ? dialect : new BigQueryDialect();
    }

    public CompiledProgram compile(InvestigationPlan plan, String tableRef) {
        List<CompiledRequest> compiled = new ArrayList<>();
        if (plan == null || plan.evidenceRequests() == null) {
            return new CompiledProgram(compiled);
        }

        int idx = 0;
        for (InvestigationPlan.EvidenceRequest request : plan.evidenceRequests()) {
            if (request == null) {
                continue;
            }
            String key = "ev_" + (idx++) + "_" + sanitize(request.requestId());
            compiled.add(compileOne(request, key, tableRef, plan.scopeFilters()));
        }
        return new CompiledProgram(compiled);
    }

    private CompiledRequest compileOne(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        String kind = request.kind() == null ? "" : request.kind().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_KINDS.contains(kind)) {
            return CompiledRequest.failed(
                    request,
                    "unsupported evidence request kind: "
                            + (request.kind() == null ? "(null)" : request.kind()));
        }
        try {
            QuerySpec spec = switch (kind) {
                case "metric_by_dimension" -> compileMetricByDimension(request, key, tableRef, scopeFilters);
                case "distribution" -> compileDistribution(request, key, tableRef, scopeFilters);
                case "correlation" -> compileCorrelation(request, key, tableRef, scopeFilters);
                case "rank", "top_n", "bottom_n" -> compileRank(request, key, tableRef, scopeFilters, kind);
                case "share" -> compileShare(request, key, tableRef, scopeFilters);
                case "concentration" -> compileConcentration(request, key, tableRef, scopeFilters);
                case "time_series" -> compileTimeSeries(request, key, tableRef, scopeFilters);
                case "slice_metric" -> compileSliceMetric(request, key, tableRef, scopeFilters);
                default -> throw new IllegalStateException("unsupported kind: " + kind);
            };
            return CompiledRequest.ok(request, spec);
        } catch (Exception e) {
            return CompiledRequest.failed(
                    request,
                    "failed to compile kind=" + kind + ": " + e.getMessage());
        }
    }

    private QuerySpec compileSliceMetric(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        InvestigationObjective.Slice slice = request.slice() != null
                ? request.slice()
                : new InvestigationObjective.Slice(List.of(
                new InvestigationObjective.Constraint(null, null, null, true)));
        var built = predicateBuilder.build(slice, scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String sql = "SELECT "
                + metricExpr(measure, predicate) + " AS value"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")";
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileMetricByDimension(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        String dimension = sqlRef(requireDimension(request));
        InvestigationObjective.Slice focal = request.focalSlice();
        InvestigationObjective.Slice baseline = request.baselineSlice();
        if (focal != null && baseline != null
                && focal.constraints() != null && baseline.constraints() != null
                && (!focal.constraints().isEmpty() || !baseline.constraints().isEmpty())) {
            var focalBuilt = predicateBuilder.build(focal, scopeFilters, tableRef, measure);
            var baselineBuilt = predicateBuilder.build(baseline, scopeFilters, tableRef, measure);
            String fp = focalBuilt.sqlPredicate();
            String bp = baselineBuilt.sqlPredicate();
            String sql = "SELECT " + dimension + " AS member,"
                    + metricExpr(measure, fp) + " AS focal_value,"
                    + metricExpr(measure, bp) + " AS baseline_value"
                    + " FROM " + tableRef
                    + " WHERE (" + fp + ") OR (" + bp + ")"
                    + " GROUP BY " + dimension;
            return new QuerySpec(key, sql, Map.of());
        }

        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String sql = "SELECT " + dimension + " AS member,"
                + metricExpr(measure, predicate) + " AS value"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY " + dimension;
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileDistribution(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        String column = sqlRef(requireDimension(request));
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, request.measure());
        String predicate = built.sqlPredicate();
        String sql = "SELECT " + column + " AS member, COUNT(*) AS count"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY " + column
                + " ORDER BY count DESC";
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileCorrelation(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        String x = textParam(request, "x");
        String y = textParam(request, "y");
        if (x == null || x.isBlank()) {
            x = request.dimension();
        }
        if (y == null || y.isBlank()) {
            y = request.measure() != null ? request.measure().column() : null;
        }
        if (x == null || x.isBlank() || y == null || y.isBlank()) {
            throw new IllegalArgumentException("correlation requires x and y columns");
        }
        x = sqlRef(x);
        y = sqlRef(y);
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, request.measure());
        String predicate = built.sqlPredicate();
        String sql = "SELECT CORR(" + x + ", " + y + ") AS correlation, COUNT(*) AS n"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")";
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileRank(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters,
            String kind
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        String dimension = sqlRef(requireDimension(request));
        int limit = intParam(request, "limit", 10);
        String side = textParam(request, "side");
        boolean ascending = "bottom_n".equals(kind)
                || (side != null && "LOWER".equalsIgnoreCase(side));
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String sql = "SELECT " + dimension + " AS member,"
                + metricExpr(measure, predicate) + " AS value"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY " + dimension
                + " ORDER BY value " + (ascending ? "ASC" : "DESC")
                + " LIMIT " + Math.max(1, limit);
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileShare(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        String dimension = sqlRef(requireDimension(request));
        int limit = intParam(request, "limit", 50);
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String valueExpr = metricExpr(measure, predicate);
        String sql = "SELECT " + dimension + " AS member,"
                + valueExpr + " AS value,"
                + dialect.safeDivide(valueExpr, "SUM(" + valueExpr + ") OVER()") + " AS share"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY " + dimension
                + " ORDER BY value DESC"
                + " LIMIT " + Math.max(1, limit);
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileConcentration(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        String dimension = sqlRef(requireDimension(request));
        int limit = intParam(request, "limit", 20);
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String valueExpr = metricExpr(measure, predicate);
        String sql = "WITH ranked AS ("
                + "SELECT " + dimension + " AS member,"
                + valueExpr + " AS value,"
                + dialect.safeDivide(valueExpr, "SUM(" + valueExpr + ") OVER()") + " AS share"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY " + dimension
                + ") "
                + "SELECT member, value, share,"
                + "SUM(share) OVER (ORDER BY value DESC "
                + "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cumulative_share "
                + "FROM ranked "
                + "ORDER BY value DESC "
                + "LIMIT " + Math.max(1, limit);
        return new QuerySpec(key, sql, Map.of());
    }

    private QuerySpec compileTimeSeries(
            InvestigationPlan.EvidenceRequest request,
            String key,
            String tableRef,
            List<InvestigationObjective.Filter> scopeFilters
    ) {
        InvestigationObjective.Measure measure = requireMeasure(request);
        String timeColumn = textParam(request, "time_column");
        if (timeColumn == null || timeColumn.isBlank()) {
            timeColumn = textParam(request, "column");
        }
        if (timeColumn == null || timeColumn.isBlank()) {
            throw new IllegalArgumentException("time_series requires time_column");
        }
        timeColumn = sqlRef(timeColumn);
        String grain = textParam(request, "time_grain");
        if (grain == null || grain.isBlank()) {
            grain = "MONTH";
        }
        String truncUnit = normalizeTimeGrain(grain);
        var built = predicateBuilder.build(
                request.slice() != null ? request.slice() : new InvestigationObjective.Slice(List.of()),
                scopeFilters, tableRef, measure);
        String predicate = built.sqlPredicate();
        String periodExpr = dialect.timestampTrunc(timeColumn, truncUnit);
        String sql = "SELECT " + periodExpr + " AS period,"
                + metricExpr(measure, predicate) + " AS value"
                + " FROM " + tableRef
                + " WHERE (" + predicate + ")"
                + " GROUP BY period"
                + " ORDER BY period";
        return new QuerySpec(key, sql, Map.of());
    }

    private String normalizeTimeGrain(String grain) {
        String g = grain.trim().toUpperCase(Locale.ROOT);
        return switch (g) {
            case "DAY", "WEEK", "MONTH", "QUARTER", "YEAR" -> g;
            default -> "MONTH";
        };
    }

    private InvestigationObjective.Measure requireMeasure(InvestigationPlan.EvidenceRequest request) {
        if (request.measure() == null || request.measure().column() == null
                || request.measure().column().isBlank()) {
            throw new IllegalArgumentException("measure.column is required");
        }
        return request.measure();
    }

    private String requireDimension(InvestigationPlan.EvidenceRequest request) {
        if (request.dimension() == null || request.dimension().isBlank()) {
            throw new IllegalArgumentException("dimension/column is required");
        }
        return request.dimension();
    }

    private String textParam(InvestigationPlan.EvidenceRequest request, String field) {
        if (request.params() == null || request.params().isNull()) {
            return null;
        }
        if (request.params().has(field) && !request.params().get(field).isNull()) {
            return request.params().get(field).asText(null);
        }
        return request.params().path(field).asText(null);
    }

    private int intParam(InvestigationPlan.EvidenceRequest request, String field, int defaultValue) {
        if (request.params() == null || request.params().isNull()) {
            return defaultValue;
        }
        var node = request.params().path(field);
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isIntegralNumber()) {
            return node.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String metricExpr(InvestigationObjective.Measure measure, String slicePredicate) {
        String cond = "(" + slicePredicate + ")";
        String agg = measure != null && measure.aggregation() != null
                ? measure.aggregation() : InvestigationObjective.AGG_SUM;
        String col = sqlRef(measure != null ? measure.column() : null);
        if (InvestigationObjective.AGG_COUNT.equalsIgnoreCase(agg)) {
            return dialect.countIf(cond);
        }
        if (InvestigationObjective.AGG_AVG.equalsIgnoreCase(agg)) {
            return dialect.safeDivide(
                    "SUM(CASE WHEN " + cond + " THEN " + col + " ELSE 0 END)",
                    dialect.countIf(cond));
        }
        return "SUM(CASE WHEN " + cond + " THEN " + col + " ELSE 0 END)";
    }

    private String sqlRef(String fieldPath) {
        return SqlFieldReferenceEmitter.emitFieldReference(fieldPath);
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "x";
        }
        return value.replaceAll("[^A-Za-z0-9]", "_");
    }

    public record CompiledRequest(
            InvestigationPlan.EvidenceRequest request,
            QuerySpec spec,
            String error
    ) {
        public boolean ok() {
            return error == null && spec != null;
        }

        public static CompiledRequest ok(InvestigationPlan.EvidenceRequest request, QuerySpec spec) {
            return new CompiledRequest(request, spec, null);
        }

        public static CompiledRequest failed(InvestigationPlan.EvidenceRequest request, String error) {
            return new CompiledRequest(request, null, error);
        }
    }

    public record CompiledProgram(List<CompiledRequest> requests) {
        public List<QuerySpec> executableSpecs() {
            List<QuerySpec> specs = new ArrayList<>();
            for (CompiledRequest cr : requests) {
                if (cr.ok()) {
                    specs.add(cr.spec());
                }
            }
            return specs;
        }

        public Map<String, String> requestToQueryKey() {
            Map<String, String> out = new LinkedHashMap<>();
            for (CompiledRequest cr : requests) {
                if (cr.ok() && cr.request() != null && cr.spec() != null) {
                    out.put(cr.request().requestId(), cr.spec().key());
                }
            }
            return out;
        }
    }
}
