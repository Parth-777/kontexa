package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.ComparisonPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.ContributionPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.CorrelationPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.DistributionPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.GrowthPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.OutlierPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.ParetoPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategy;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.PresentationStrategyResolver;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.RankingPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.ScalarPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.TrendPresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.strategy.VariancePresentation;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutivePresentationStatisticsBuilderTest {

    private ExecutivePresentationBuilder presentationBuilder;
    private ExecutivePresentationStatisticsBuilder statisticsBuilder;

    @BeforeEach
    void setUp() {
        List<PresentationStrategy> strategies = List.of(
                new ScalarPresentation(),
                new RankingPresentation(),
                new DistributionPresentation(),
                new TrendPresentation(),
                new GrowthPresentation(),
                new ParetoPresentation(),
                new OutlierPresentation(),
                new VariancePresentation(),
                new ContributionPresentation(),
                new ComparisonPresentation(),
                new CorrelationPresentation());
        SemanticMetricFormatter formatter = new SemanticMetricFormatter();
        ExecutivePresentationProperties properties = new ExecutivePresentationProperties();
        presentationBuilder = new ExecutivePresentationBuilder(
                formatter,
                properties,
                new PresentationStrategyResolver(),
                strategies);
        statisticsBuilder = new ExecutivePresentationStatisticsBuilder(formatter, properties);
    }

    @Test
    void scalarStatisticsExposeMetricValue() {
        CanonicalQueryModel model = scalarModel();
        List<Map<String, Object>> rows = List.of(Map.of("total_revenue", 7_946_237.89));

        ExecutivePresentation enriched = enrich(model, rows);

        assertEquals(7_946_237.89, enriched.statistics().get("metricValue"));
        assertEquals(1, enriched.statistics().get("rowCount"));
        assertTrue(enriched.toMap().containsKey("statistics"));
    }

    @Test
    void rankingStatisticsExposeLeaderGapAndSpread() {
        CanonicalQueryModel model = rankingModel();
        List<Map<String, Object>> rows = List.of(
                row("Alpha", 300),
                row("Beta", 200),
                row("Gamma", 100));

        ExecutivePresentation enriched = enrich(model, rows);
        Map<String, Object> stats = enriched.statistics();

        assertEquals(3, stats.get("rowCount"));
        assertEquals("Alpha", stats.get("leaderName"));
        assertEquals(300.0, stats.get("leaderValue"));
        assertEquals("Beta", stats.get("secondName"));
        assertEquals(200.0, stats.get("secondValue"));
        assertEquals("Gamma", stats.get("lastName"));
        assertEquals(100.0, stats.get("lastValue"));
        assertEquals(100.0, stats.get("valueGap"));
        assertEquals(50.0, stats.get("valueGapPercent"));
        assertEquals(200.0, stats.get("overallSpread"));
        assertEquals(600.0, stats.get("totalAcrossRows"));
    }

    @Test
    void distributionStatisticsExposeShares() {
        CanonicalQueryModel model = distributionModel();
        List<Map<String, Object>> rows = List.of(
                Map.of("region", "North", "total_revenue", 1_200_000),
                Map.of("region", "South", "total_revenue", 800_000));

        ExecutivePresentation enriched = enrich(model, rows);
        Map<String, Object> stats = enriched.statistics();

        assertEquals(2, stats.get("rowCount"));
        assertEquals(2_000_000.0, stats.get("total"));
        assertEquals("North", stats.get("largestCategory"));
        assertEquals(60.0, stats.get("largestShare"));
        assertEquals("South", stats.get("smallestCategory"));
        assertEquals(40.0, stats.get("smallestShare"));
    }

    @Test
    void comparisonStatisticsExposeDifferences() {
        CanonicalQueryModel model = comparisonModel();
        List<Map<String, Object>> rows = List.of(Map.of("metric_a", 100, "metric_b", 150));

        ExecutivePresentation enriched = enrich(model, rows);
        Map<String, Object> stats = enriched.statistics();

        assertEquals(100.0, stats.get("metricA"));
        assertEquals(150.0, stats.get("metricB"));
        assertEquals(50.0, stats.get("absoluteDifference"));
        assertEquals(50.0, stats.get("percentDifference"));
    }

    @Test
    void contributionStatisticsExposePercentages() {
        CanonicalQueryModel model = contributionScalarModel();
        List<Map<String, Object>> rows = List.of(Map.of("segment_revenue", 250, "total_revenue", 1_000));

        ExecutivePresentation enriched = enrich(model, rows);
        Map<String, Object> stats = enriched.statistics();

        assertEquals(250.0, stats.get("numerator"));
        assertEquals(1_000.0, stats.get("denominator"));
        assertEquals(25.0, stats.get("contributionPercent"));
        assertEquals(75.0, stats.get("remainingPercent"));
    }

    @Test
    void trendStatisticsExposeGrowth() {
        CanonicalQueryModel model = trendModel();
        List<Map<String, Object>> rows = List.of(
                Map.of("month", "Jan", "total_revenue", 100),
                Map.of("month", "Feb", "total_revenue", 150));

        ExecutivePresentation enriched = enrich(model, rows);
        Map<String, Object> stats = enriched.statistics();

        assertEquals(150.0, stats.get("latestValue"));
        assertEquals(100.0, stats.get("previousValue"));
        assertEquals(50.0, stats.get("absoluteGrowth"));
        assertEquals(50.0, stats.get("percentGrowth"));
    }

    @Test
    void omitsUnavailableStatisticsInsteadOfFabricating() {
        CanonicalQueryModel model = contributionScalarModel();
        List<Map<String, Object>> rows = List.of(Map.of("segment_revenue", 250, "total_revenue", 0));

        ExecutivePresentation enriched = enrich(model, rows);

        assertFalse(enriched.statistics().containsKey("contributionPercent"));
        assertFalse(enriched.statistics().containsKey("remainingPercent"));
        assertEquals(250.0, enriched.statistics().get("numerator"));
        assertEquals(0.0, enriched.statistics().get("denominator"));
    }

    private ExecutivePresentation enrich(CanonicalQueryModel model, List<Map<String, Object>> rows) {
        ExecutivePresentation presentation = presentationBuilder.build(model, rows);
        return statisticsBuilder.enrich(presentation, model, rows);
    }

    private static CanonicalQueryModel scalarModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                null, List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("SCALAR", 0.9, "", List.of(), null, null));
    }

    private static CanonicalQueryModel rankingModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("operation_cost", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("RANKING", 0.9, "", List.of("company_name"), null, null));
    }

    private static CanonicalQueryModel distributionModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(),
                null,
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("DISTRIBUTION", 0.8, "", List.of("region"), null, null));
    }

    private static CanonicalQueryModel comparisonModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("metric_a", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("RATIO",
                        new CanonicalQueryModel.MeasureSpec("metric_b", "SUM")),
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("COMPARISON", 0.8, "", List.of(), "metric_b", null));
    }

    private static CanonicalQueryModel contributionScalarModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("segment_revenue", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("CONTRIBUTION",
                        new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM")),
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("CONTRIBUTION", 0.8, "", List.of(), "total_revenue", null));
    }

    private static CanonicalQueryModel trendModel() {
        return new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("month", "MONTH"),
                List.of(),
                null,
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("TREND", 0.8, "", List.of("month"), null, null));
    }

    private static Map<String, Object> row(String company, double cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("company_name", company);
        row.put("operation_cost", cost);
        return row;
    }
}
