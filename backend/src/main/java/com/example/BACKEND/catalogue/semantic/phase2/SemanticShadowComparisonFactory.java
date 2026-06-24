package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.SemanticShadowComparison;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class SemanticShadowComparisonFactory {

    private static final int SAMPLE_ROW_LIMIT = 8;

    public SemanticShadowComparison build(
            String plannerMode,
            String question,
            AnalysisPlan legacyPlan,
            GptSemanticPlanningOrchestrator.GptPlanningOutcome gptOutcome,
            List<QuerySpec> legacySpecs,
            List<QuerySpec> gptSpecs,
            List<QueryResult> legacyResults,
            List<QueryResult> gptResults,
            String error
    ) {
        StructuredSemanticPlan gptPlan = gptOutcome != null ? gptOutcome.semanticPlan() : null;
        SemanticPlanValidationResult validation = gptOutcome != null
                ? gptOutcome.validation() : SemanticPlanValidationResult.fail(error != null ? error : "not-run");
        AnalysisPlan gptAnalysis = gptOutcome != null ? gptOutcome.analysisPlan() : null;

        double confidence = gptPlan != null ? gptPlan.confidence() : 0.0;
        Map<String, Object> divergence = computeDivergence(legacyPlan, gptAnalysis, legacyResults, gptResults);

        return new SemanticShadowComparison(
                plannerMode,
                "legacy",
                question,
                confidence,
                validation.valid(),
                validation.issues(),
                mapAnalysisPlan(legacyPlan),
                mapStructuredPlan(gptPlan),
                mapAnalysisPlan(gptAnalysis),
                mapSql(legacySpecs),
                mapSql(gptSpecs),
                mapExecution(legacyResults),
                mapExecution(gptResults),
                divergence,
                error);
    }

    private static Map<String, Object> computeDivergence(
            AnalysisPlan legacy,
            AnalysisPlan gpt,
            List<QueryResult> legacyResults,
            List<QueryResult> gptResults
    ) {
        Map<String, Object> d = new LinkedHashMap<>();
        int legacyRows = totalRows(legacyResults);
        int gptRows = totalRows(gptResults);

        boolean intentMatch = legacy != null && gpt != null
                && legacy.intent() == gpt.intent();
        boolean metricMatch = legacy != null && gpt != null
                && eq(legacy.primaryMetric(), gpt.primaryMetric());
        boolean dimensionMatch = legacy != null && gpt != null
                && eq(legacy.dimension(), gpt.dimension());

        d.put("intent_match", intentMatch);
        d.put("metric_match", metricMatch);
        d.put("dimension_match", dimensionMatch);
        d.put("legacy_row_count", legacyRows);
        d.put("gpt_row_count", gptRows);
        d.put("legacy_sql_count", legacyResults != null ? legacyResults.size() : 0);
        d.put("gpt_sql_count", gptResults != null ? gptResults.size() : 0);
        d.put("row_count_delta", gptRows - legacyRows);
        d.put("summary", summarize(intentMatch, metricMatch, dimensionMatch, legacyRows, gptRows, gpt));
        return d;
    }

    private static String summarize(
            boolean intentMatch,
            boolean metricMatch,
            boolean dimensionMatch,
            int legacyRows,
            int gptRows,
            AnalysisPlan gpt
    ) {
        if (gpt == null || !gpt.executable()) {
            return "GPT plan blocked or invalid — legacy served";
        }
        if (intentMatch && metricMatch && dimensionMatch && legacyRows == gptRows) {
            return "Plans align — same intent, bindings, and row count";
        }
        List<String> parts = new ArrayList<>();
        if (!intentMatch) parts.add("intent differs");
        if (!metricMatch) parts.add("metric differs");
        if (!dimensionMatch) parts.add("dimension differs");
        if (legacyRows != gptRows) {
            parts.add("row count " + legacyRows + " vs " + gptRows);
        }
        return parts.isEmpty() ? "Minor divergence" : String.join("; ", parts);
    }

    private static int totalRows(List<QueryResult> results) {
        if (results == null) return 0;
        return results.stream()
                .mapToInt(r -> r.rows() != null ? r.rows().size() : 0)
                .sum();
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    static Map<String, Object> mapAnalysisPlan(AnalysisPlan plan) {
        if (plan == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", plan.intent() != null ? plan.intent().name() : null);
        m.put("primaryMetric", plan.primaryMetric());
        m.put("dimension", plan.dimension());
        m.put("relationshipVariable", plan.relationshipVariable());
        m.put("secondaryMetric", plan.secondaryMetric());
        m.put("executable", plan.executable());
        m.put("blockingReasons", plan.blockingReasons());
        StructuredPlanProjection p = plan.structuredProjection();
        if (p != null && p != StructuredPlanProjection.empty()) {
            m.put("structuredProjection", Map.of(
                    "dimensions", p.dimensions(),
                    "primaryAggregation", Objects.toString(p.primaryAggregation(), ""),
                    "orderColumn", Objects.toString(p.orderColumn(), ""),
                    "orderDirection", Objects.toString(p.orderDirection(), ""),
                    "resultLimit", p.resultLimit() != null ? p.resultLimit() : "",
                    "timeGrain", Objects.toString(p.timeGrain(), "")
            ));
        }
        return m;
    }

    private static Map<String, Object> mapStructuredPlan(StructuredSemanticPlan plan) {
        if (plan == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", plan.intent());
        m.put("metric", plan.metric());
        m.put("secondaryMetric", plan.secondaryMetric());
        m.put("dimensions", plan.dimensions());
        m.put("relationshipVariable", plan.relationshipVariable());
        m.put("confidence", plan.confidence());
        m.put("reasoning", plan.reasoning());
        if (plan.aggregations() != null) {
            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("primary", plan.aggregations().primary());
            agg.put("secondary", plan.aggregations().secondary());
            m.put("aggregations", agg);
        }
        if (plan.ordering() != null) {
            Map<String, Object> ord = new LinkedHashMap<>();
            ord.put("column", plan.ordering().column());
            ord.put("direction", plan.ordering().direction());
            m.put("ordering", ord);
        }
        m.put("limit", plan.limit());
        m.put("timeGrain", plan.timeGrain());
        return m;
    }

    private static List<Map<String, Object>> mapSql(List<QuerySpec> specs) {
        if (specs == null || specs.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuerySpec s : specs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", s.key());
            row.put("sql", s.sql());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> mapExecution(List<QueryResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (QueryResult r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r.key());
            row.put("elapsedMs", r.elapsedMs());
            int rowCount = r.rows() != null ? r.rows().size() : 0;
            row.put("rowCount", rowCount);
            row.put("sampleRows", sampleRows(r.rows()));
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> sampleRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream().limit(SAMPLE_ROW_LIMIT).toList();
    }
}
