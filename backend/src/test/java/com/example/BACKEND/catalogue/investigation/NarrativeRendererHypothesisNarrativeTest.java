package com.example.BACKEND.catalogue.investigation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeRendererHypothesisNarrativeTest {

    private final NarrativeRenderer renderer = new NarrativeRenderer();

    @Test
    void whyNarrativeIntroducesHypothesesPurposesAndInsufficiency() {
        InvestigationPlan.Phenomenon phenomenon = new InvestigationPlan.Phenomenon(
                "desktop traffic higher than mobile",
                new InvestigationObjective.Measure("totals.visits", "SUM", List.of()));

        List<InvestigationPlan.Hypothesis> hypotheses = List.of(
                new InvestigationPlan.Hypothesis(
                        "H1",
                        "Desktop traffic is higher because organic traffic favors desktop.",
                        InvestigationPlan.Hypothesis.ALTERNATIVE),
                new InvestigationPlan.Hypothesis(
                        "H2",
                        "Desktop traffic is higher because direct traffic dominates.",
                        InvestigationPlan.Hypothesis.PRIMARY),
                new InvestigationPlan.Hypothesis(
                        "H3",
                        "Desktop traffic is higher because desktop has higher engagement.",
                        InvestigationPlan.Hypothesis.REJECTED_CANDIDATE));

        InvestigationPlan.Insufficiency insufficiency = new InvestigationPlan.Insufficiency(
                true,
                List.of("marketing spend unavailable", "campaign attribution unavailable"),
                List.of());

        List<EvidencePack.RequestResult> results = List.of(
                EvidencePack.RequestResult.success(
                        "ev_focal", "slice_metric", "FOCAL", null, "COMPARISON",
                        List.of("q1"), List.of(Map.of("value", 1412)), null),
                EvidencePack.RequestResult.success(
                        "ev_base", "slice_metric", "BASELINE", null, "COMPARISON",
                        List.of("q2"), List.of(Map.of("value", 250)), null),
                EvidencePack.RequestResult.success(
                        "ev_h2", "metric_by_dimension", null, "H2", "PRIMARY",
                        List.of("q3"),
                        List.of(Map.of("member", "direct", "value", 872)),
                        null));

        EvidencePack pack = new EvidencePack(
                "Why is desktop traffic higher?",
                phenomenon,
                hypotheses,
                insufficiency,
                results,
                new EvidencePack.Coverage(3, 3, 0),
                new EvidencePack.Confidence(EvidencePack.Confidence.HIGH, List.of("test")),
                "ga_sessions");

        NarrativeOutput out = renderer.render(pack);
        String explanation = out.explanation().toLowerCase();

        assertTrue(explanation.contains("competing hypotheses"));
        assertTrue(explanation.contains("h2"));
        assertTrue(explanation.contains("direct traffic dominates")
                || explanation.contains("h2 (primary)"));
        assertTrue(explanation.contains("untestable") || explanation.contains("h3"));
        assertTrue(explanation.contains("catalogue gaps") || explanation.contains("marketing spend"));
        assertTrue(out.keyFindings().stream().anyMatch(f -> f.toLowerCase().contains("primary evidence")));
        assertTrue(out.keyFindings().stream().anyMatch(f -> f.toLowerCase().contains("comparison evidence")));
        assertTrue(explanation.contains("linked") || explanation.contains("warehouse"));
        assertFalse(explanation.contains("h1 has"),
                "must not invent support for H1 without linked warehouse rows");
        assertTrue(explanation.contains("no warehouse evidence was linked to h1")
                || explanation.contains("unsupported"));
    }

    @Test
    void unanswerableInsufficiencyLeadsHeadlineAndAvoidsCausalBusinessReading() {
        InvestigationPlan.Phenomenon phenomenon = new InvestigationPlan.Phenomenon(
                "high bounce rate",
                new InvestigationObjective.Measure("totals.bounces", "SUM", List.of()));

        EvidencePack pack = new EvidencePack(
                "Explain high bounce rate.",
                phenomenon,
                List.of(),
                new InvestigationPlan.Insufficiency(
                        false,
                        List.of("engagement quality unavailable"),
                        List.of("catalogue missing conversion funnel")),
                List.of(EvidencePack.RequestResult.success(
                        "ev1", "slice_metric", "FOCAL", null, "COMPARISON",
                        List.of("q1"), List.of(Map.of("value", 100)), null),
                        EvidencePack.RequestResult.success(
                                "ev2", "slice_metric", "BASELINE", null, "COMPARISON",
                                List.of("q2"), List.of(Map.of("value", 40)), null)),
                new EvidencePack.Coverage(2, 2, 0),
                new EvidencePack.Confidence(EvidencePack.Confidence.HIGH, List.of("test")),
                "ga_sessions");

        NarrativeOutput out = renderer.render(pack);
        assertTrue(out.headline().toLowerCase().contains("insufficient catalogue evidence"));
        assertTrue(out.explanation().toLowerCase().contains("cannot fully prove"));
        assertTrue(out.explanation().toLowerCase().contains("conversion funnel"));
        assertEquals(EvidencePack.Confidence.MODERATE, out.confidenceLevel());
        assertFalse(out.explanation().toLowerCase().contains("business reading"),
                "must not soft-close a causal claim when answerable=false");
    }
}
