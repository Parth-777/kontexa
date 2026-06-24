package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlRenderHints;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredPlanProjectionSqlTest {

    @Test
    void distributionSumAggregationUsesMetricNotRowCount() {
        var projection = new StructuredPlanProjection(
                List.of("region"), "SUM", null, null, null, null, null);
        AnalysisPlan plan = new AnalysisPlan(
                "Emissions footprint across operating regions",
                "oil",
                AnalysisIntent.DISTRIBUTION,
                "carbon_emission_tons", "carbon emission tons",
                "region", "region", "region",
                null, null, null, null,
                true, List.of(),
                null, List.of(), projection);

        var specs = SqlTemplateTestHarness.create().planner.plan(plan,
                MetricResolutionTestSupport.oilBundle());
        assertFalse(specs.isEmpty());
        String sql = specs.get(0).sql().toUpperCase();
        assertTrue(sql.contains("SUM(CARBON_EMISSION_TONS)"), sql);
        assertFalse(sql.contains("COUNT(*) AS ROW_COUNT"));
    }

    @Test
    void relationshipSqlUsesDistinctOperands() {
        var projection = StructuredPlanProjection.empty();
        AnalysisPlan plan = new AnalysisPlan(
                "Does carbon emission correlate with profit margin?",
                "oil",
                AnalysisIntent.RELATIONSHIP,
                "carbon_emission_tons", "carbon emission tons",
                null, null, null,
                "profit_margin", "profit margin",
                "profit_margin", "profit margin",
                true, List.of(),
                null, List.of(), projection);

        var specs = SqlTemplateTestHarness.create().planner.plan(plan,
                MetricResolutionTestSupport.oilBundle());
        assertEquals(1, specs.size());
        String sql = specs.get(0).sql();
        assertTrue(sql.contains("CORR(profit_margin, carbon_emission_tons)")
                || sql.contains("CORR(carbon_emission_tons, profit_margin)"), sql);
        assertFalse(sql.contains("CORR(carbon_emission_tons, carbon_emission_tons)"));
    }

    @Test
    void trendAppliesTimeGrainAndOrdering() {
        var projection = new StructuredPlanProjection(
                List.of("recorded_date"), "SUM", null,
                "recorded_date", "ASC", null, "MONTH");
        AnalysisPlan plan = new AnalysisPlan(
                "Show revenue trend by month for oil operations",
                "oil",
                AnalysisIntent.TREND,
                "total_revenue", "total revenue",
                "recorded_date", "recorded_date", "recorded_date",
                null, null, null, null,
                true, List.of(),
                null, List.of(), projection);

        var specs = SqlTemplateTestHarness.create().planner.plan(plan,
                MetricResolutionTestSupport.oilBundle());
        assertFalse(specs.isEmpty());
        String sql = specs.get(0).sql().toUpperCase();
        assertTrue(sql.contains("DATE_TRUNC(RECORDED_DATE, MONTH)"), sql);
        assertTrue(sql.contains("ORDER BY MONTH_PERIOD ASC"), sql);
    }

    @Test
    void rankingHonorsStructuredLimit() {
        var projection = new StructuredPlanProjection(
                List.of("region"), "SUM", null,
                "maintainence_cost", "DESC", 3, null);
        AnalysisPlan plan = new AnalysisPlan(
                "Which region has the highest maintenance cost?",
                "oil",
                AnalysisIntent.RANKING,
                "maintainence_cost", "maintainence cost",
                "region", "region", "region",
                null, null, null, null,
                true, List.of(),
                null, List.of(), projection);

        var specs = SqlTemplateTestHarness.create().planner.plan(plan,
                MetricResolutionTestSupport.oilBundle());
        assertFalse(specs.isEmpty());
        assertTrue(specs.get(0).sql().toUpperCase().contains("LIMIT 3"));
    }
}
