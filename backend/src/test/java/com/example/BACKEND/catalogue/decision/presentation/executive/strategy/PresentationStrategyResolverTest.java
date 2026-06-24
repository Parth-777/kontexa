package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PresentationStrategyResolverTest {

    private final PresentationStrategyResolver resolver = new PresentationStrategyResolver();

    @Test
    void resolvesScalarWhenOnlyMeasurePresent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                null, List.of(), null, null, null, null, null);

        assertEquals(PresentationStrategyType.SCALAR, resolver.resolve(model));
    }

    @Test
    void resolvesRankingWhenOrderingPresent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("operation_cost", "DESC"),
                null,
                null);

        assertEquals(PresentationStrategyType.RANKING, resolver.resolve(model));
    }

    @Test
    void resolvesParetoFromMetadataIntent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(),
                null,
                null,
                new CanonicalQueryModel.OrderSpec("total_revenue", "DESC"),
                null,
                new CanonicalQueryModel.PlannerMetadata("PARETO", 0.9, "", List.of("region"), null, null));

        assertEquals(PresentationStrategyType.PARETO, resolver.resolve(model));
    }

    @Test
    void resolvesGrowthFromMetadataIntent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("order_date", "month"),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("GROWTH", 0.9, "", List.of("order_date"), null, null));

        assertEquals(PresentationStrategyType.GROWTH, resolver.resolve(model));
    }

    @Test
    void resolvesOutlierFromMetadataIntent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("OUTLIER", 0.85, "", List.of("company_name"), null, null));

        assertEquals(PresentationStrategyType.OUTLIER, resolver.resolve(model));
    }

    @Test
    void resolvesVarianceFromMetadataIntent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("operation_cost", "SUM"),
                new CanonicalQueryModel.PartitionSpec("company_name", null),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("VARIANCE", 0.85, "", List.of("company_name"), null, null));

        assertEquals(PresentationStrategyType.VARIANCE, resolver.resolve(model));
    }

    @Test
    void resolvesDistributionWhenPartitionWithoutTimeGrain() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("region", null),
                List.of(), null, null, null, null, null);

        assertEquals(PresentationStrategyType.DISTRIBUTION, resolver.resolve(model));
    }

    @Test
    void resolvesTrendWhenTimeGrainPresent() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("total_revenue", "SUM"),
                new CanonicalQueryModel.PartitionSpec("order_date", "month"),
                List.of(), null, null, null, null,
                new CanonicalQueryModel.PlannerMetadata("TREND", 0.9, "", List.of("order_date"), null, null));

        assertEquals(PresentationStrategyType.TREND, resolver.resolve(model));
    }

    @Test
    void resolvesContributionFromRatioKind() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("airport_fee", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("CONTRIBUTION",
                        new CanonicalQueryModel.MeasureSpec("total_amount", "SUM")),
                null, null, null, null);

        assertEquals(PresentationStrategyType.CONTRIBUTION, resolver.resolve(model));
    }

    @Test
    void resolvesComparisonFromNonContributionRatio() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("metric_a", "SUM"),
                null,
                List.of(),
                new CanonicalQueryModel.RatioSpec("COMPARISON",
                        new CanonicalQueryModel.MeasureSpec("metric_b", "SUM")),
                null, null, null, null);

        assertEquals(PresentationStrategyType.COMPARISON, resolver.resolve(model));
    }

    @Test
    void resolvesCorrelationFromBivariate() {
        CanonicalQueryModel model = new CanonicalQueryModel(
                new CanonicalQueryModel.MeasureSpec("trip_distance", "AVG"),
                null,
                List.of(),
                null,
                new CanonicalQueryModel.BivariateSpec("trip_distance", "fare_amount", "CORR"),
                null, null, null);

        assertEquals(PresentationStrategyType.CORRELATION, resolver.resolve(model));
    }
}
