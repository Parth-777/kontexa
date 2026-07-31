package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders contrast-driven investigation SQL from resolved slice predicates.
 */
@Component
public class EvidenceQueryPlanner {

    public EvidencePlan plan(ResolvedInvestigation resolved) {
        QuerySpec headline = headlineSpec(resolved);
        List<DimensionQuery> dimensionQueries = new ArrayList<>();
        int idx = 0;
        for (CandidateDimension dim : resolved.candidateDimensions()) {
            String key = "inv_dim_" + (idx++) + "_" + sanitize(dim.column());
            dimensionQueries.add(new DimensionQuery(dim, dimensionSpec(resolved, dim, key)));
        }
        return new EvidencePlan(headline, dimensionQueries);
    }

    private QuerySpec headlineSpec(ResolvedInvestigation resolved) {
        String focal = resolved.focalPredicate().sqlPredicate();
        String baseline = resolved.baselinePredicate().sqlPredicate();
        InvestigationObjective.Measure measure = resolved.objective().measure();
        String sql = "SELECT "
                + metricExpr(measure, focal) + " AS focal_total,"
                + metricExpr(measure, baseline) + " AS baseline_total"
                + " FROM " + resolved.tableRef()
                + scanWhere(focal, baseline);
        return new QuerySpec("inv_headline", sql, Map.of());
    }

    private QuerySpec dimensionSpec(ResolvedInvestigation resolved, CandidateDimension dim, String key) {
        String focal = resolved.focalPredicate().sqlPredicate();
        String baseline = resolved.baselinePredicate().sqlPredicate();
        InvestigationObjective.Measure measure = resolved.objective().measure();
        String sql = "SELECT " + dim.column() + " AS member,"
                + metricExpr(measure, focal) + " AS focal_value,"
                + metricExpr(measure, baseline) + " AS baseline_value"
                + " FROM " + resolved.tableRef()
                + scanWhere(focal, baseline)
                + " GROUP BY " + dim.column()
                + " ORDER BY ABS(" + metricExpr(measure, focal) + " - "
                + metricExpr(measure, baseline) + ") DESC";
        return new QuerySpec(key, sql, Map.of());
    }

    private String metricExpr(InvestigationObjective.Measure measure, String slicePredicate) {
        String cond = "(" + slicePredicate + ")";
        if (InvestigationObjective.AGG_COUNT.equals(measure.aggregation())) {
            return "COUNTIF(" + cond + ")";
        }
        if (InvestigationObjective.AGG_AVG.equals(measure.aggregation())) {
            return "SAFE_DIVIDE(SUM(CASE WHEN " + cond + " THEN " + measure.column()
                    + " ELSE 0 END), COUNTIF(" + cond + "))";
        }
        return "SUM(CASE WHEN " + cond + " THEN " + measure.column() + " ELSE 0 END)";
    }

    private String scanWhere(String focal, String baseline) {
        return " WHERE (" + focal + ") OR (" + baseline + ")";
    }

    private String sanitize(String column) {
        return column == null ? "x" : column.replaceAll("[^A-Za-z0-9]", "_");
    }

    public record DimensionQuery(CandidateDimension dimension, QuerySpec spec) {}

    public record EvidencePlan(QuerySpec headline, List<DimensionQuery> dimensionQueries) {}
}
