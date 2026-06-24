package com.example.BACKEND.catalogue.decision.verification;

import java.util.List;
import java.util.Map;

/**
 * Developer-mode inspection of analytical execution — SQL is source of truth.
 */
public record QueryDebugPanel(
        List<SqlEntry>           generatedSql,
        List<RepairAttemptEntry> repairAttempts,
        List<CandidatePlanEntry> candidatePlans,
        String                   selectedPlan,
        List<GroupedRowEntry>    groupedResults,
        double                   aggregationTotal,
        double                   groupedSum,
        double                   reconcileDeltaPct,
        ConfidenceDecomposition  confidence,
        List<String>             verificationViolations,
        boolean                  verificationPassed
) {
    public record SqlEntry(
            String       key,
            String       sql,
            int          rowCount,
            long         elapsedMs,
            String       metric,
            String       dimension,
            boolean      success,
            String       strategy,
            List<String> groupByColumns,
            String       whereClause,
            String       failureReason,
            List<java.util.Map<String, Object>> sampleRows
    ) {}

    public record RepairAttemptEntry(
            String queryKey,
            String strategy,
            int    attemptIndex,
            int    rowCount,
            long   elapsedMs,
            String failureReason,
            boolean success,
            String sql
    ) {}

    public record CandidatePlanEntry(
            String candidateId,
            String label,
            String metric,
            String dimension,
            double score
    ) {}

    public record GroupedRowEntry(
            String segment,
            double value,
            double sharePct,
            int    rank
    ) {}

    public static QueryDebugPanel empty() {
        return new QueryDebugPanel(
                List.of(), List.of(), List.of(), "", List.of(), 0, 0, 0,
                ConfidenceDecomposition.from(null, 0, 0, false),
                List.of(), false);
    }

    public Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("generated_sql", generatedSql.stream().map(s -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("key", s.key());
            row.put("sql", s.sql());
            row.put("row_count", s.rowCount());
            row.put("elapsed_ms", s.elapsedMs());
            row.put("metric", s.metric());
            row.put("dimension", s.dimension());
            row.put("success", s.success());
            row.put("strategy", s.strategy() != null ? s.strategy() : "");
            row.put("group_by_columns", s.groupByColumns() != null ? s.groupByColumns() : List.of());
            row.put("where_clause", s.whereClause() != null ? s.whereClause() : "");
            row.put("failure_reason", s.failureReason() != null ? s.failureReason() : "");
            row.put("sample_rows", s.sampleRows() != null ? s.sampleRows() : List.of());
            return row;
        }).toList());
        m.put("repair_attempts", repairAttempts.stream().map(r -> Map.of(
                "query_key", r.queryKey(),
                "strategy", r.strategy(),
                "attempt_index", r.attemptIndex(),
                "row_count", r.rowCount(),
                "elapsed_ms", r.elapsedMs(),
                "failure_reason", r.failureReason() != null ? r.failureReason() : "",
                "success", r.success(),
                "sql", r.sql()
        )).toList());
        m.put("candidate_plans", candidatePlans.stream().map(c -> Map.of(
                "candidate_id", c.candidateId(),
                "label", c.label(),
                "metric", c.metric(),
                "dimension", c.dimension(),
                "score", c.score()
        )).toList());
        m.put("selected_plan", selectedPlan != null ? selectedPlan : "");
        m.put("grouped_results", groupedResults.stream().map(g -> Map.of(
                "segment", g.segment(),
                "value", g.value(),
                "share_pct", g.sharePct(),
                "rank", g.rank()
        )).toList());
        m.put("aggregation_total", aggregationTotal);
        m.put("grouped_sum", groupedSum);
        m.put("reconcile_delta_pct", reconcileDeltaPct);
        m.put("confidence", Map.of(
                "sql_validity", confidence.sqlValidity(),
                "aggregation_consistency", confidence.aggregationConsistency(),
                "statistical_separation", confidence.statisticalSeparation(),
                "row_coverage", confidence.rowCoverage(),
                "narrative_certainty", confidence.narrativeCertainty(),
                "composite", confidence.composite()
        ));
        m.put("verification_violations", verificationViolations);
        m.put("verification_passed", verificationPassed);
        return m;
    }
}
