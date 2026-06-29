package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification that chart visualization is cardinality-aware while the warehouse rows,
 * statistics, and executive table remain complete.
 */
class ChartCardinalityPresentationTest {

    private ExecutivePresentationBuilder builder;
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
        ExecutivePresentationProperties properties = new ExecutivePresentationProperties();
        builder = new ExecutivePresentationBuilder(
                new SemanticMetricFormatter(),
                properties,
                new PresentationStrategyResolver(),
                strategies);
        statisticsBuilder = new ExecutivePresentationStatisticsBuilder(
                new SemanticMetricFormatter(), properties);
    }

    @Test
    void rankingChartKeepsTopFiveWhileStatisticsAndTableUseAllRows() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("location_id", null),
                List.of(), null, null,
                new CanonicalQueryModel.OrderSpec("revenue", "DESC"), null,
                new CanonicalQueryModel.PlannerMetadata("RANKING", 0.9, "", List.of("location_id"), null, null));

        List<Map<String, Object>> rows = rows(260);
        ExecutivePresentation presentation = builder.build(model, rows);

        ChartSpec chart = builder.toChartSpec(presentation, rows);
        assertEquals(ChartSpec.ChartType.HBAR, chart.getType());
        assertEquals(5, chart.getData().size());
        assertEquals(5, chart.getDisplayedRows());
        assertEquals(260, chart.getTotalRows());
        assertEquals(0, chart.getAggregatedRows());

        // Executive table is unchanged by chart reduction (ranking shows its own top rows).
        assertEquals(5, presentation.table().rows().size());

        // Statistics are computed over the complete 260-row warehouse result set.
        ExecutivePresentation enriched = statisticsBuilder.enrich(presentation, model, rows);
        assertEquals(260, ((Number) enriched.statistics().get("rowCount")).intValue());
    }

    @Test
    void distributionChartCollapsesToTopFivePlusOther() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("location_id", null),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("DISTRIBUTION", 0.8, "", List.of("location_id"), null, null));

        List<Map<String, Object>> rows = rows(6);
        ExecutivePresentation presentation = builder.build(model, rows);

        ChartSpec chart = builder.toChartSpec(presentation, rows);
        assertEquals(ChartSpec.ChartType.HBAR, chart.getType());
        assertEquals(6, chart.getData().size()); // top 5 + Other
        assertEquals(5, chart.getDisplayedRows());
        assertEquals(6, chart.getTotalRows());
        assertEquals(1, chart.getAggregatedRows());

        Map<String, Object> other = chart.getData().getLast();
        assertEquals(ChartCardinalityReducer.OTHER_LABEL, other.get("location_id"));

        // Table renders all six grouped rows (no Top-N truncation on the executive table).
        assertEquals(6, presentation.table().rows().size());
    }

    @Test
    void contributionLargeCardinalityUsesDonutTopFivePlusOther() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("location_id", null),
                List.of(),
                new CanonicalQueryModel.RatioSpec("CONTRIBUTION",
                        new CanonicalQueryModel.MeasureSpec("revenue", "SUM")),
                null, null, null,
                new CanonicalQueryModel.PlannerMetadata("CONTRIBUTION", 0.8, "", List.of("location_id"), null, null));

        List<Map<String, Object>> rows = rows(260);
        ExecutivePresentation presentation = builder.build(model, rows);

        ChartSpec chart = builder.toChartSpec(presentation, rows);
        assertEquals(ChartSpec.ChartType.DONUT, chart.getType());
        assertEquals(6, chart.getData().size());
        assertEquals(5, chart.getDisplayedRows());
        assertEquals(260, chart.getTotalRows());
        assertEquals(255, chart.getAggregatedRows());
        assertTrue(chart.getSubtitle().contains("Top 5 of 260"));
    }

    @Test
    void scalarProducesNoChart() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("revenue", "SUM"),
                null, List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("SCALAR", 0.9, "", List.of(), null, null));

        ExecutivePresentation presentation = builder.build(model, List.of(Map.of("revenue", 1000)));
        assertNull(builder.toChartSpec(presentation, List.of(Map.of("revenue", 1000))));
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("location_id", "loc-" + i);
            row.put("revenue", i);
            rows.add(row);
        }
        return rows;
    }
}
