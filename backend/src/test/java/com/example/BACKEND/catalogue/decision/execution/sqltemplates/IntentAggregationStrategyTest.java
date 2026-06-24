package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.investigation.AnalyticalInvestigationIntent;
import com.example.BACKEND.catalogue.decision.semantics.AnalyticalRelationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAggregationStrategyTest {

    private IntentAggregationStrategy strategy;
    private GroupedMetricSqlBuilder sqlBuilder;

    @BeforeEach
    void setUp() {
        strategy = new IntentAggregationStrategy();
        sqlBuilder = new GroupedMetricSqlBuilder(strategy);
    }

    static Stream<Arguments> contributionQuestions() {
        return Stream.of(
                Arguments.of("How do airport rides contribute to revenue?", "total_amount", "airport_flag"),
                Arguments.of("How do weekend rides contribute to revenue?", "total_amount", "weekend_flag"),
                Arguments.of("How does trip distance affect revenue?", "total_amount", "trip_distance")
        );
    }

    @ParameterizedTest
    @MethodSource("contributionQuestions")
    void contributionQuestions_useSumNotAvg(String question, String metric, String dimension) {
        TemplateContext ctx = TemplateContext.contribution(
                question, "yellow_taxi_trips", metric, dimension, "primary");
        String sql = sqlBuilder.renderGroupedQuery(ctx);
        assertTrue(sql.contains("SUM(" + metric + ")"), "Expected SUM for: " + question + "\n" + sql);
        assertFalse(sql.toUpperCase().contains("AVG(" + metric.toUpperCase()),
                "Must not AVG revenue metric for: " + question + "\n" + sql);
    }

    @Test
    void tipShare_usesSum() {
        TemplateContext ctx = new TemplateContext(
                "How does tip amount contribute to revenue?",
                AnalyticalIntentKind.CONTRIBUTION,
                "yellow_taxi_trips", "tip_amount", null, "", "composition", "composition");
        String sql = sqlBuilder.renderGroupedQuery(ctx);
        assertTrue(sql.contains("SUM(tip_amount)"));
        assertFalse(sql.contains("AVG(tip_amount)"));
    }

    @Test
    void dimensionBreakdown_routesToContributionSqlIntent() {
        assertEquals(AnalyticalIntentKind.CONTRIBUTION,
                strategy.sqlIntentForRelationship(AnalyticalRelationship.DIMENSION_BREAKDOWN));
    }

    @Test
    void efficiency_allowsAvgFallback() {
        assertTrue(strategy.allowsAvgFallback(AnalyticalIntentKind.EFFICIENCY));
        assertFalse(strategy.allowsAvgFallback(AnalyticalIntentKind.CONTRIBUTION));
    }

    @Test
    void contributionSql_usesMetricColumnAsAlias() {
        TemplateContext ctx = TemplateContext.contribution(
                "Which oil field generates the highest profit?",
                "oil_operations", "profit_margin", "oil_field", "primary");
        String sql = sqlBuilder.renderGroupedQuery(ctx);
        assertTrue(sql.contains("SUM(profit_margin)"));
        assertTrue(sql.contains("AS profit_margin"), sql);
        assertFalse(sql.contains("AS revenue"));
    }

    @Test
    void investigationContribution_usesSumWithShare() {
        AggregationSpec spec = strategy.forInvestigationIntent(AnalyticalInvestigationIntent.CONTRIBUTION);
        assertEquals(MetricAggregation.SUM, spec.aggregation());
        assertTrue(spec.includeSharePercent());
    }
}
