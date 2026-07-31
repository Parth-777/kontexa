package com.example.BACKEND.catalogue.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeRendererAnswerQualityTest {

    private final NarrativeRenderer renderer = new NarrativeRenderer();

    @Test
    void correlationAnswerStatesStrengthAndMetric() {
        EvidencePack pack = pack(
                "How does trip distance influence total amount?",
                "trip distance",
                "total_amount",
                "SUM",
                List.of(EvidencePack.RequestResult.success(
                        "c1", "correlation", null, List.of("q1"),
                        List.of(Map.of("correlation", 0.72, "n", 1000)))));
        NarrativeOutput out = renderer.render(pack);
        assertTrue(out.headline().toLowerCase().contains("strong"));
        assertTrue(out.explanation().toLowerCase().contains("metric under investigation"));
        assertTrue(out.explanation().toLowerCase().contains("business reading"));
        assertTrue(out.explanation().toLowerCase().contains("confidence"));
    }

    @Test
    void pairedSlicesExplainMetricAndBusinessCaveatForZeroBaseline() {
        EvidencePack pack = pack(
                "Why do payment type 1 users tip more than payment type 2?",
                "payment type",
                "tip_amount",
                "AVG",
                List.of(
                        EvidencePack.RequestResult.success(
                                "f", "slice_metric", "FOCAL", List.of("q1"),
                                List.of(Map.of("value", 3.14))),
                        EvidencePack.RequestResult.success(
                                "b", "slice_metric", "BASELINE", List.of("q2"),
                                List.of(Map.of("value", 0.0)))));
        NarrativeOutput out = renderer.render(pack);
        assertTrue(out.headline().contains("3.14"));
        assertTrue(out.headline().contains("0.00") || out.headline().contains("0.0"));
        assertTrue(out.explanation().toLowerCase().contains("average tip amount")
                || out.explanation().toLowerCase().contains("tip amount"));
        assertTrue(out.explanation().toLowerCase().contains("business reading"));
        assertTrue(out.explanation().toLowerCase().contains("zero")
                || out.explanation().toLowerCase().contains("cash")
                || out.explanation().toLowerCase().contains("measurement"));
    }

    @Test
    void timeSeriesAnswerStatesDirection() {
        EvidencePack pack = pack(
                "How has revenue changed over time?",
                "revenue",
                "total_amount",
                "SUM",
                List.of(EvidencePack.RequestResult.success(
                        "ts", "time_series", null, List.of("q1"),
                        List.of(
                                Map.of("period", "2024-01", "value", 1000),
                                Map.of("period", "2024-02", "value", 1200)))));
        NarrativeOutput out = renderer.render(pack);
        assertTrue(out.headline().toLowerCase().contains("increased"));
        assertTrue(out.explanation().toLowerCase().contains("trajectory")
                || out.explanation().toLowerCase().contains("business reading"));
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
                "nyc_taxi");
    }
}
