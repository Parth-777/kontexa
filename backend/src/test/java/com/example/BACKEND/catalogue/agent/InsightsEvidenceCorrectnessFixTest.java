package com.example.BACKEND.catalogue.agent;

import com.example.BACKEND.catalogue.agent.executive.MetricDisplayFormatter;
import com.example.BACKEND.catalogue.agent.executive.MetricEvidenceAnnotator;
import com.example.BACKEND.catalogue.agent.executive.MetricSemantics;
import com.example.BACKEND.catalogue.agent.executive.MaterialityRanker;
import com.example.BACKEND.catalogue.agent.executive.InsightCandidate;
import com.example.BACKEND.catalogue.agent.executive.InsightLens;
import com.example.BACKEND.catalogue.agent.executive.RevenueModelLensAgent;
import com.example.BACKEND.catalogue.agent.executive.GeneralDiscoveryLensAgent;
import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.charts.InsightChartMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression suite for Insights V2 evidence-correctness fixes.
 */
class InsightsEvidenceCorrectnessFixTest {

    @Test
    void scaledCurrencyUsesCanonicalDisplay() {
        MetricSemantics sem = MetricSemantics.of("currency", 1e-6, "sessions");
        String display = MetricDisplayFormatter.formatMetric(6_117_162_000d, sem);
        assertEquals("$6.12K", display);
        assertEquals(6117.162, MetricDisplayFormatter.chartValue(6_117_162_000d, sem), 0.01);
    }

    @Test
    void metricWithoutScaleFailsClosedNoDollar() {
        MetricSemantics unknown = MetricSemantics.unknown();
        String display = MetricDisplayFormatter.formatMetric(6_117_162_000d, unknown);
        assertFalse(display.contains("$"), "must not invent currency: " + display);
    }

    @Test
    void segmentContributionUsesGrandTotalNotTopN() {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("segment", "United States");
        top.put("segment_revenue", 4_510_000_000d);
        // TOP_N sum would be 4.51e9 only (100%), grand total is 6.12e9
        MetricEvidenceAnnotator.annotateSegmentRows(
                List.of(top),
                "segment_revenue",
                6_120_000_000d,
                MetricSemantics.of("currency", 1e-6, "sessions"),
                "totals.transactionRevenue");
        assertEquals(73.7, (Double) top.get(MetricEvidenceAnnotator.SHARE_OF_TOTAL), 0.1);
        assertNotEquals(100.0, (Double) top.get(MetricEvidenceAnnotator.SHARE_OF_TOTAL), 0.1);
    }

    @Test
    void topNSubsetDoesNotBecomeTotalWithoutGrandTotal() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment", "US");
        row.put("segment_revenue", 100);
        assertFalse(MetricEvidenceAnnotator.hasGrandTotalShare(row));
    }

    @Test
    void singleRowConcentrationRequiresAtLeastTwoSegments() {
        Map<String, Object> tautology = new LinkedHashMap<>();
        tautology.put("top_share_pct", 100.0);
        assertFalse(MetricEvidenceAnnotator.concentrationEvidenceOk(tautology, 1));

        Map<String, Object> oneSeg = new LinkedHashMap<>();
        oneSeg.put(MetricEvidenceAnnotator.SHARE_OF_TOTAL, 100.0);
        oneSeg.put(MetricEvidenceAnnotator.GRAND_TOTAL, 1000.0);
        oneSeg.put(MetricEvidenceAnnotator.SEGMENT_COUNT, 1);
        assertFalse(MetricEvidenceAnnotator.concentrationEvidenceOk(oneSeg, 1));

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put(MetricEvidenceAnnotator.SHARE_OF_TOTAL, 73.7);
        ok.put(MetricEvidenceAnnotator.GRAND_TOTAL, 1000.0);
        ok.put(MetricEvidenceAnnotator.SEGMENT_COUNT, 3);
        assertTrue(MetricEvidenceAnnotator.concentrationEvidenceOk(ok, 3));
    }

    @Test
    void countNotLabeledAsTransactions() {
        RevenueModelLensAgent lens = new RevenueModelLensAgent();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_revenue", 1000);
        row.put("avg_revenue", 100);
        row.put("row_count", 34);
        row.put(MetricEvidenceAnnotator.GRAIN, "sessions");
        row.put(MetricEvidenceAnnotator.DISPLAY_UNIT, "currency");
        row.put(MetricEvidenceAnnotator.SCALE, 1.0);

        CollectedData cd = new CollectedData("REVENUE: total summary totals.transactionRevenue",
                "SELECT 1", List.of(row));
        List<InsightCandidate> out = lens.generate(List.of(cd));
        assertEquals(1, out.size());
        assertFalse(out.get(0).claim().toLowerCase().contains("transaction"),
                out.get(0).claim());
        assertTrue(out.get(0).claim().toLowerCase().contains("session")
                        || out.get(0).claim().toLowerCase().contains("record"),
                out.get(0).claim());
    }

    @Test
    void materialityRankingIgnoresRawHeterogeneousMagnitude() {
        DecisionMemoryService memory = mock(DecisionMemoryService.class);
        when(memory.adjustConfidence(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(3));
        MaterialityRanker ranker = new MaterialityRanker(memory);

        // Huge raw micros-looking score vs modest share-based score
        InsightCandidate hugeRaw = new InsightCandidate(
                "Huge raw field", InsightLens.REVENUE, "", 6_120_000, // would dominate old ranker
                "INFO", "MEDIUM", List.of("REVENUE: x"), List.of(), List.of("raw"), "Finance", null);
        InsightCandidate shareBased = new InsightCandidate(
                "US holds 73.7% of total", InsightLens.REVENUE, "", 40,
                "RISK", "HIGH", List.of("REVENUE: sources"), List.of(), List.of("country"), "Finance", "US");

        List<InsightCandidate> top = ranker.selectTop(List.of(hugeRaw, shareBased), "t", 75);
        assertFalse(top.isEmpty());
        // After bounding impactScore to ≤50, share + RISK/HIGH should beat unbounded-looking raw
        assertTrue(top.get(0).claim().contains("73.7%")
                        || top.stream().anyMatch(c -> c.claim().contains("73.7%")),
                "share-based materiality should survive ranking");
        // Huge raw is capped — should not crush everything solely via magnitude
        double hugeScored = top.stream()
                .filter(c -> c.claim().contains("Huge"))
                .mapToDouble(InsightCandidate::impactScore)
                .findFirst()
                .orElse(0);
        double shareScored = top.stream()
                .filter(c -> c.claim().contains("73.7%"))
                .mapToDouble(InsightCandidate::impactScore)
                .findFirst()
                .orElse(0);
        assertTrue(shareScored >= hugeScored,
                "share " + shareScored + " should rank >= capped raw " + hugeScored);
    }

    @Test
    void unsupportedLlmDeltaRejected() {
        InsightEvidenceValidator validator = new InsightEvidenceValidator();
        AgentDashboardResult.InsightCard card = baseCard(
                "Revenue base",
                "Revenue increased by $180M this period",
                List.of("Revenue increased by $180M", "34 records"));
        List<CollectedData> evidence = List.of(summaryEvidence());
        List<AgentDashboardResult.InsightCard> kept =
                validator.filterSupported(List.of(card), evidence);
        assertTrue(kept.isEmpty(), "invented delta without time series must fail");
    }

    @Test
    void unsupportedLlmTrendRejected() {
        InsightEvidenceValidator validator = new InsightEvidenceValidator();
        AgentDashboardResult.InsightCard card = baseCard(
                "US source",
                "US revenue increased significantly",
                List.of("Revenue from the U.S. increased significantly", "73.7% of total"));
        List<CollectedData> evidence = List.of(sourcesEvidenceWithGrandTotal());
        List<AgentDashboardResult.InsightCard> kept =
                validator.filterSupported(List.of(card), evidence);
        assertTrue(kept.isEmpty(), "trend language without multi-period evidence must fail");
    }

    @Test
    void unsupportedPercentageOfTotalRejected() {
        InsightEvidenceValidator validator = new InsightEvidenceValidator();
        AgentDashboardResult.InsightCard card = baseCard(
                "Mix",
                "Location is 100% of total revenue",
                List.of("100% of total revenue", "dominates"));
        // Evidence has only TOP_N-relative numbers — no grand_total / share_of_total
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment", "x");
        row.put("segment_revenue", 100);
        CollectedData cd = new CollectedData("REVENUE: sources by dim metric", "SELECT 1", List.of(row));
        List<AgentDashboardResult.InsightCard> kept =
                validator.filterSupported(List.of(card), List.of(cd));
        assertTrue(kept.isEmpty());
    }

    @Test
    void postPolishValidationCatchesHallucination() {
        InsightEvidenceValidator validator = new InsightEvidenceValidator();
        // Programmatic card would pass; polished version invents $ and trend
        AgentDashboardResult.InsightCard polished = baseCard(
                "Revenue surged to $6.12B",
                "Revenue increased significantly to $6.12B",
                List.of("Revenue increased significantly", "$6.12B of total"));
        List<AgentDashboardResult.InsightCard> kept =
                validator.filterSupported(List.of(polished), List.of(summaryEvidenceNoScale()));
        assertTrue(kept.isEmpty());
    }

    @Test
    void chartAndNarrativeUseIdenticalScaledValues() {
        MetricSemantics sem = MetricSemantics.of("currency", 1e-6, "sessions");
        double raw = 4_510_000_000d;
        String narrative = MetricDisplayFormatter.formatMetric(raw, sem);
        double chart = MetricDisplayFormatter.chartValue(raw, sem);
        assertEquals("$4.51K", narrative);
        assertEquals(4510.0, chart, 0.01);

        InsightChartMapper mapper = new InsightChartMapper();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment", "United States");
        row.put("segment_revenue", raw);
        MetricEvidenceAnnotator.putSemantics(row, sem, "totals.transactionRevenue");
        CollectedData cd = new CollectedData(
                "REVENUE: sources by geoNetwork.country totals.transactionRevenue",
                "SELECT 1", List.of(row));
        AgentDashboardResult.InsightCard card = baseCard("US", "share", List.of("73.7%"));
        card.setAgentName("Revenue Model (Finance)");
        card.setSourceColumns(List.of("geoNetwork.country"));
        ChartSpec chartSpec = mapper.chartFor(card, List.of(cd));
        assertNotNull(chartSpec);
        assertEquals("currency", chartSpec.getValueFormat());
        Object y = chartSpec.getData().get(0).get("segment_revenue");
        assertEquals(4510.0, ((Number) y).doubleValue(), 0.01);
    }

    @Test
    void revenueLensUsesGrandTotalShareLanguage() {
        RevenueModelLensAgent lens = new RevenueModelLensAgent();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment", "United States");
        row.put("segment_revenue", 4_510_000_000d);
        row.put(MetricEvidenceAnnotator.SHARE_OF_TOTAL, 73.7);
        row.put(MetricEvidenceAnnotator.GRAND_TOTAL, 6_120_000_000d);
        row.put(MetricEvidenceAnnotator.SEGMENT_COUNT, 3);
        row.put(MetricEvidenceAnnotator.DISPLAY_UNIT, "currency");
        row.put(MetricEvidenceAnnotator.SCALE, 1e-6);
        CollectedData cd = new CollectedData(
                "REVENUE: sources by geoNetwork.country totals.transactionRevenue",
                "SELECT 1", List.of(row));
        List<InsightCandidate> out = lens.generate(List.of(cd));
        assertEquals(1, out.size());
        assertTrue(out.get(0).claim().contains("of total"));
        assertTrue(out.get(0).claim().contains("$4.51K"));
        assertFalse(out.get(0).claim().contains("$4.51B"));
    }

    @Test
    void discoveryConcentrationSkippedWithoutGate() {
        GeneralDiscoveryLensAgent lens = new GeneralDiscoveryLensAgent();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", "revenue");
        row.put("dimension", "networkLocation");
        row.put("top_segment", "Comcast");
        row.put("top_share_pct", 100.0);
        // missing grand total → null candidate
        CollectedData cd = new CollectedData(
                "DISCOVERY: totals.transactionRevenue concentration networkLocation",
                "SELECT 1", List.of(row));
        assertTrue(lens.generate(List.of(cd)).isEmpty());
    }

    private static AgentDashboardResult.InsightCard baseCard(String title, String desc, List<String> reasons) {
        AgentDashboardResult.InsightCard card = new AgentDashboardResult.InsightCard();
        card.setTitle(title);
        card.setDescription(desc);
        card.setReasons(reasons);
        card.setAgentName("Revenue Model (Finance)");
        card.setBadge("INFO");
        card.setImpactLevel("MEDIUM");
        card.setMetricHighlights(List.of(
                new AgentDashboardResult.MetricHighlight("Size", "United States"),
                new AgentDashboardResult.MetricHighlight("Change", "73.7%"),
                new AgentDashboardResult.MetricHighlight("Where", "Country")
        ));
        return card;
    }

    private static CollectedData summaryEvidence() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total_revenue", 6120000000d);
        row.put("avg_revenue", 180000000d);
        row.put("row_count", 34);
        return new CollectedData("REVENUE: total summary m", "SELECT 1", List.of(row));
    }

    private static CollectedData summaryEvidenceNoScale() {
        return summaryEvidence();
    }

    private static CollectedData sourcesEvidenceWithGrandTotal() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("segment", "United States");
        row.put("segment_revenue", 4510000000d);
        row.put(MetricEvidenceAnnotator.SHARE_OF_TOTAL, 73.7);
        row.put(MetricEvidenceAnnotator.GRAND_TOTAL, 6120000000d);
        row.put(MetricEvidenceAnnotator.SEGMENT_COUNT, 3);
        return new CollectedData("REVENUE: sources by country m", "SELECT 1", List.of(row));
    }
}
