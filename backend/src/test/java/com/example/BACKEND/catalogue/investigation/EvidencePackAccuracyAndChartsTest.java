package com.example.BACKEND.catalogue.investigation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePackAccuracyAndChartsTest {

    private final EvidencePackValidator validator = new EvidencePackValidator();
    private final EvidencePackChartRecommender charts = new EvidencePackChartRecommender();
    private final NarrativeRenderer narrative = new NarrativeRenderer(charts, validator);

    @Test
    void validatesSucceededRequestsAgainstRowShapes() throws Exception {
        EvidencePack pack = comparisonPack();
        EvidencePackValidator.ValidationReport report = validator.validate(pack);
        assertTrue(report.ok(), () -> report.issues().toString());
        assertEquals(2, report.requestsChecked());
        assertTrue(report.requestTraces().stream()
                .allMatch(t -> "VALID".equals(t.get("status"))));

        Path out = Path.of("target/evidence-pack-validation-trace.json");
        Files.writeString(out, new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(validator.toMap(report)));
    }

    @Test
    void flagsEmptyRowsAsError() {
        EvidencePack pack = pack(
                "q",
                "subject",
                "tip_amount",
                "AVG",
                List.of(EvidencePack.RequestResult.success(
                        "empty", "slice_metric", "FOCAL", List.of("q1"), List.of())));
        EvidencePackValidator.ValidationReport report = validator.validate(pack);
        assertFalse(report.ok());
        assertTrue(report.issues().stream().anyMatch(i -> "EMPTY_ROWS".equals(i.code())));
    }

    @Test
    void recommendsComparisonAndAttachesToResult() {
        EvidencePack pack = comparisonPack();
        List<EvidencePackChartRecommender.Recommendation> recs = charts.recommend(pack);
        assertFalse(recs.isEmpty());
        assertEquals(ChartSpec.ChartType.GROUPED_BAR, recs.getFirst().chart().getType());

        NarrativeOutput out = narrative.render(pack);
        DecisionRunResult result = narrative.toResult("run-1", pack, out);
        assertNotNull(result.analytical().executiveCard().visualization());
        assertEquals(ChartSpec.ChartType.GROUPED_BAR,
                result.analytical().executiveCard().visualization().getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> investigation = result.routing().investigation();
        assertTrue(investigation.containsKey("charts"));
        assertTrue(investigation.containsKey("evidence_validation"));
        assertTrue(out.headline().toLowerCase().contains("average tip amount")
                || out.headline().toLowerCase().contains("tip"));
        assertTrue(out.explanation().toLowerCase().contains("metric under investigation"));
        assertTrue(out.explanation().toLowerCase().contains("confidence"));
        assertTrue(out.explanation().toLowerCase().contains("business reading"));
    }

    @Test
    void recommendsLineForTimeSeriesAndParetoForConcentration() {
        EvidencePack ts = pack(
                "How has revenue changed over time?",
                "revenue",
                "total_amount",
                "SUM",
                List.of(EvidencePack.RequestResult.success(
                        "ts", "time_series", null, List.of("q1"),
                        List.of(
                                Map.of("period", "2024-01", "value", 100),
                                Map.of("period", "2024-02", "value", 140)))));
        assertEquals(ChartSpec.ChartType.LINE, charts.primary(ts).getType());

        EvidencePack conc = pack(
                "Is revenue concentrated?",
                "customers",
                "total_amount",
                "SUM",
                List.of(EvidencePack.RequestResult.success(
                        "c", "concentration", null, List.of("q1"),
                        List.of(
                                map("member", "c1", "value", 500, "share", 0.4, "cumulative_share", 0.4),
                                map("member", "c2", "value", 300, "share", 0.24, "cumulative_share", 0.64)))));
        assertEquals(ChartSpec.ChartType.PARETO, charts.primary(conc).getType());
    }

    private static EvidencePack comparisonPack() {
        return pack(
                "Why do payment type 1 users tip more than payment type 2?",
                "payment type",
                "tip_amount",
                "AVG",
                List.of(
                        EvidencePack.RequestResult.success(
                                "pt1", "slice_metric", "FOCAL", List.of("q1"),
                                List.of(Map.of("value", 3.14)),
                                "SELECT ... WHERE payment_type = 1"),
                        EvidencePack.RequestResult.success(
                                "pt2", "slice_metric", "BASELINE", List.of("q2"),
                                List.of(Map.of("value", 0.0)),
                                "SELECT ... WHERE payment_type = 2")));
    }

    private static EvidencePack pack(
            String question,
            String subject,
            String metric,
            String agg,
            List<EvidencePack.RequestResult> results
    ) {
        InvestigationPlan.Phenomenon phenomenon = new InvestigationPlan.Phenomenon(
                subject,
                new InvestigationObjective.Measure(metric, agg, List.of()));
        int ok = (int) results.stream().filter(EvidencePack.RequestResult::ok).count();
        return new EvidencePack(
                question,
                phenomenon,
                results,
                new EvidencePack.Coverage(results.size(), ok, results.size() - ok),
                new EvidencePack.Confidence(EvidencePack.Confidence.HIGH, List.of("test")),
                "yellow_tripdata_2024_01");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
