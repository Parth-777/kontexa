package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreAggregatedMaterializationTest {

    private final SchemaProfiler schemaProfiler = new SchemaProfiler();
    private final AnalyticalQueryMaterializer materializer = new AnalyticalQueryMaterializer(
            new DerivedDimensionMaterializer(),
            new NumericDimensionBucketer(new MetricBucketingEngine()),
            new MaterializationPlanBuilder(new PresentationLabelResolver()),
            new GroupByExecutor(),
            new PresentationLabelResolver());

    @Test
    void oilFieldProfitRows_areNotDiscarded() {
        List<Map<String, Object>> rows = List.of(
                row("Santos Basin", 79370.7),
                row("Basra Field", 78753.5),
                row("Permian Basin", 65000.0),
                row("North Sea", 54000.0),
                row("Gulf Field", 48000.0),
                row("Shale Field", 42000.0),
                row("Offshore A", 39000.0),
                row("Offshore B", 35000.0));

        var shape = GroupedWarehouseResultDetector.detect(rows);
        assertNotNull(shape);
        assertEquals("entity", shape.dimensionColumn());
        assertEquals("revenue", shape.metricColumn());

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, (InvestigationPlan) null);

        assertTrue(result.hasContent(), "Pre-aggregated warehouse rows must materialize");
        assertEquals(8, result.primaryGrouping().groupCount());
        assertEquals("Santos Basin", result.primaryGrouping().rankedEntries().getFirst().entityKey());
    }

    @Test
    void profitMarginAlias_isDetectedAsMetric() {
        List<Map<String, Object>> rows = List.of(
                metricRow("Field A", 100.0),
                metricRow("Field B", 200.0),
                metricRow("Field C", 150.0));

        var shape = GroupedWarehouseResultDetector.detect(rows);
        assertNotNull(shape);
        assertEquals("profit_margin", shape.metricColumn());
    }

    @Test
    void numericGroupKey_withSharePct_isGroupedAggregate() {
        List<Map<String, Object>> rows = List.of(
                numericGroupRow(1, 12000.0, 11.2),
                numericGroupRow(2, 14500.0, 13.5),
                numericGroupRow(3, 13200.0, 12.3),
                numericGroupRow(4, 12800.0, 11.9),
                numericGroupRow(5, 14100.0, 13.1),
                numericGroupRow(6, 15000.0, 14.0),
                numericGroupRow(7, 18000.0, 16.8));

        var shape = GroupedWarehouseResultDetector.detect(rows);
        assertNotNull(shape, "numeric group keys must be detected as grouped aggregate");
        assertEquals("group_code", shape.dimensionColumn());
        assertEquals("total_amount", shape.metricColumn());
        assertEquals("share_pct", shape.shareColumn());

        var profile = schemaProfiler.profile(rows);
        var result = materializer.materialize(rows, profile, null);
        assertTrue(result.hasContent());
        assertEquals(7, result.primaryGrouping().groupCount());
        assertEquals(16.8, result.primaryGrouping().rankedEntries().getFirst().sharePct(), 0.1);
    }

    @Test
    void numericHourKey_materializes() {
        List<Map<String, Object>> rows = List.of(
                hourRow(8, 50000, 12.5),
                hourRow(12, 80000, 20.0),
                hourRow(17, 75000, 18.8),
                hourRow(20, 60000, 15.0));

        var shape = GroupedWarehouseResultDetector.detect(rows);
        assertNotNull(shape);
        assertEquals("hour_of_day", shape.dimensionColumn());

        var result = materializer.materialize(rows, schemaProfiler.profile(rows), null);
        assertTrue(result.hasContent());
        assertEquals(4, result.primaryGrouping().groupCount());
    }

    private Map<String, Object> numericGroupRow(int code, double amount, double share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group_code", code);
        m.put("total_amount", amount);
        m.put("share_pct", share);
        return m;
    }

    private Map<String, Object> hourRow(int hour, double amount, double share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hour_of_day", hour);
        m.put("total_amount", amount);
        m.put("share_pct", share);
        return m;
    }

    @Test
    void weekPeriodTrendRows_materialize() {
        List<Map<String, Object>> rows = List.of(
                trendRow("2024-01-14", 2.44e9, 1.17),
                trendRow("2023-03-26", 2.24e9, 1.07),
                trendRow("2024-08-04", 2.12e9, 1.01),
                trendRow("2025-01-12", 2.02e9, 0.97));

        assertNotNull(GroupedWarehouseResultDetector.detect(rows));
        var result = materializer.materialize(rows, schemaProfiler.profile(rows), null);
        assertTrue(result.hasContent());
        assertEquals(4, result.primaryGrouping().groupCount());
    }

    private Map<String, Object> trendRow(String week, double revenue, double share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("week_period", week);
        m.put("total_revenue", revenue);
        m.put("share_pct", share);
        return m;
    }

    private Map<String, Object> row(String entity, double revenue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", entity);
        m.put("revenue", revenue);
        return m;
    }

    private Map<String, Object> metricRow(String entity, double profitMargin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", entity);
        m.put("profit_margin", profitMargin);
        return m;
    }
}
