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
import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutivePresentationBuilderTest {

    private ExecutivePresentationBuilder builder;

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
        builder = new ExecutivePresentationBuilder(
                new SemanticMetricFormatter(),
                new ExecutivePresentationProperties(),
                new PresentationStrategyResolver(),
                strategies);
    }

    @Test
    void scalarProducesKpiCard() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                null, List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("SCALAR", 0.9, "", List.of(), null, null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(Map.of("total_revenue", 7.94623789482E7)));

        assertEquals("SCALAR", presentation.type());
        assertEquals(1, presentation.kpis().size());
        assertTrue(presentation.kpis().getFirst().formattedValue().contains("M"));
        assertFalse(presentation.insights().isEmpty());
    }

    @Test
    void rankingProducesFormattedRankedTable() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("operation_cost", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("RANKING", 0.9, "", List.of("company_name"), null, null));

        List<Map<String, Object>> rows = List.of(
                row("PetroNova Energy", 1.5627360503500023E10),
                row("Atlas Petroleum", 1.5557127095410007E10),
                row("TerraFuel Corp", 1.5101206298930014E10),
                row("Apex Hydrocarbons", 1.5008896589319998E10),
                row("GlobalDrill Resources", 1.4990896884210003E10),
                row("Other Corp", 1.0E9));

        ExecutivePresentation presentation = builder.build(model, rows);

        assertEquals("RANKING", presentation.type());
        assertTrue(presentation.table().hasContent());
        assertEquals(5, presentation.table().rows().size());
        assertEquals("1", presentation.table().rows().getFirst().get("rank"));
        assertTrue(presentation.table().rows().getFirst().get("value").contains("B"));
        assertEquals("HBAR", presentation.charts().getFirst().chartType());
    }

    @Test
    void distributionProducesGroupedTableWithShareAndBarChart() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(),
                null,
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("DISTRIBUTION", 0.8, "", List.of("region"), null, null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(
                        Map.of("region", "North", "total_revenue", 1_200_000),
                        Map.of("region", "South", "total_revenue", 800_000)));

        assertEquals("DISTRIBUTION", presentation.type());
        assertEquals(2, presentation.table().rows().size());
        assertTrue(presentation.table().columns().stream()
                .anyMatch(c -> "share_pct".equals(c.key())));
        assertEquals("HBAR", presentation.charts().getFirst().chartType());
    }

    @Test
    void paretoProducesCumulativeShareColumn() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("total_revenue", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("PARETO", 0.85, "", List.of("region"), null, null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(
                        Map.of("region", "North", "total_revenue", 800_000),
                        Map.of("region", "South", "total_revenue", 200_000)));

        assertEquals("PARETO", presentation.type());
        assertTrue(presentation.table().columns().stream()
                .anyMatch(c -> "cumulative_pct".equals(c.key())));
    }

    @Test
    void contributionGroupedTableIncludesShareColumn() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_amount", "SUM"),
                new CanonicalQueryModel.PartitionSpec("airport_flag", null),
                List.of(),
                new CanonicalQueryModel.RatioSpec("CONTRIBUTION",
                        new CanonicalQueryModel.MeasureSpec("Airport_fee", "SUM")),
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("CONTRIBUTION", 0.8, "", List.of("airport_flag"), "Airport_fee", null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(
                        Map.of("airport_flag", "true", "total_amount", 1000),
                        Map.of("airport_flag", "false", "total_amount", 3000)));

        assertEquals("CONTRIBUTION", presentation.type());
        assertTrue(presentation.table().columns().stream()
                .anyMatch(c -> "share_pct".equals(c.key())));
    }

    @Test
    void contributionScalarShowsDashWhenDenominatorZero() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("segment_revenue", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("CONTRIBUTION",
                        new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM")),
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("CONTRIBUTION", 0.8, "", List.of(), "total_revenue", null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(Map.of("segment_revenue", 1000, "total_revenue", 0)));

        assertFalse(presentation.kpis().stream()
                .anyMatch(k -> k.formattedValue().toLowerCase().contains("nan")));
        assertEquals("—", presentation.kpis().stream()
                .filter(k -> "Contribution".equals(k.label()))
                .findFirst()
                .orElseThrow()
                .formattedValue());
        assertTrue(presentation.insights().stream()
                .anyMatch(i -> i.contains("denominator could not be computed")));
        assertFalse(presentation.toMap().toString().toLowerCase().contains("nan"));
    }

    @Test
    void nestedSemanticPathsBindWarehouseSqlAliasesIntoTableAndChart() {
        // Warehouse returns SQL SELECT aliases; CQM still uses catalogue leaf paths.
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("totals.visits", "SUM"),
                new CanonicalQueryModel.PartitionSpec("trafficSource.source", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("totals.visits", "DESC"),
                1,
                new CanonicalQueryModel.PlannerMetadata(
                        "RANKING", 0.9, "", List.of("trafficSource.source"), null, null));

        List<Map<String, Object>> warehouseRows = List.of(
                Map.of("source", "(direct)", "visits", 872));

        ExecutivePresentation presentation = builder.build(model, warehouseRows);

        assertEquals("RANKING", presentation.type());
        assertTrue(presentation.table().hasContent());
        assertEquals(1, presentation.table().rows().size());
        assertEquals("(direct)", presentation.table().rows().getFirst().get("segment"));
        assertFalse(presentation.table().rows().getFirst().get("value").contains("—"));
        assertTrue(presentation.table().rows().getFirst().get("value").contains("872")
                || presentation.table().rows().getFirst().get("value").contains("872.0"));

        ChartSpec chart = builder.toChartSpec(presentation, warehouseRows);
        assertNotNull(chart);
        assertFalse(chart.getData().isEmpty());
        assertEquals("(direct)", chart.getData().getFirst().get("trafficSource.source"));
        assertEquals(872, ((Number) chart.getData().getFirst().get("totals.visits")).intValue());
    }

    @Test
    void deviceCategoryVisitsAliasesPopulateDistributionTableAndShare() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("totals.visits", "SUM"),
                new CanonicalQueryModel.PartitionSpec("device.deviceCategory", null),
                List.of(),
                null,
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata(
                        "DISTRIBUTION", 0.9, "", List.of("device.deviceCategory"), null, null));

        List<Map<String, Object>> warehouseRows = List.of(
                Map.of("deviceCategory", "desktop", "visits", 1412),
                Map.of("deviceCategory", "mobile", "visits", 250),
                Map.of("deviceCategory", "tablet", "visits", 49));

        ExecutivePresentation presentation = builder.build(model, warehouseRows);

        assertEquals("DISTRIBUTION", presentation.type());
        assertEquals(3, presentation.table().rows().size());
        assertEquals("desktop", presentation.table().rows().getFirst().get("segment"));
        assertFalse(presentation.table().rows().getFirst().get("value").contains("—"));
        assertTrue(presentation.table().columns().stream().anyMatch(c -> "share_pct".equals(c.key())));
        assertFalse(presentation.table().rows().getFirst().get("share_pct").contains("—"));

        ChartSpec chart = builder.toChartSpec(presentation, warehouseRows);
        assertNotNull(chart);
        assertEquals(3, chart.getData().size());
        assertEquals(1412, ((Number) chart.getData().getFirst().get("totals.visits")).intValue());
    }

    @Test
    void comparisonShowsSemanticInsightWhenBaselineZero() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("metric_a", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("RATIO",
                        new CanonicalQueryModel.MeasureSpec("metric_b", "SUM")),
                null,
                null,
                null,
                new CanonicalQueryModel.PlannerMetadata("COMPARISON", 0.8, "", List.of(), "metric_b", null));

        ExecutivePresentation presentation = builder.build(
                model,
                List.of(Map.of("metric_a", 0, "metric_b", 500)));

        assertEquals("—", presentation.kpis().stream()
                .filter(k -> "Percent difference".equals(k.label()))
                .findFirst()
                .orElseThrow()
                .formattedValue());
        assertTrue(presentation.insights().stream()
                .anyMatch(i -> i.contains("baseline metric is zero or missing")));
    }

    private static Map<String, Object> row(String company, double cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("company_name", company);
        row.put("operation_cost", cost);
        return row;
    }
}
