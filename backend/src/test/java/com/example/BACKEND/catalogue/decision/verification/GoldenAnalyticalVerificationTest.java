package com.example.BACKEND.catalogue.decision.verification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenAnalyticalVerificationTest {

    private AnalyticalVerificationEngine engine;
    private StatisticalNarrativeGuard narrativeGuard;

    @BeforeEach
    void setUp() {
        engine = new AnalyticalVerificationEngine();
        narrativeGuard = new StatisticalNarrativeGuard();
    }

    static Stream<GoldenAnalyticalBenchmark> benchmarks() {
        return GoldenAnalyticalTestSuite.all().stream();
    }

    @ParameterizedTest
    @MethodSource("benchmarks")
    void syntheticDataset_passesVerification(GoldenAnalyticalBenchmark benchmark) {
        var materialized = SyntheticBenchmarkDatasets.forBenchmark(benchmark);
        var report = engine.verify(materialized, List.of(), benchmark);
        assertTrue(report.passed(), benchmark.id() + " violations: " + report.violations());
        assertTrue(report.groupedTotal() > 0);
        assertTrue(Math.abs(report.reconcileDeltaPct()) < 5.0);
    }

    @Test
    void groupedSum_reconcilesToOverallTotal() {
        var materialized = SyntheticBenchmarkDatasets.revenueByTripDistance();
        double groupedSum = materialized.primaryGrouping().rankedEntries().stream()
                .mapToDouble(e -> e.totalValue()).sum();
        assertTrue(Math.abs(groupedSum - materialized.primaryGrouping().totalValueSum()) < 1.0);
    }

    @Test
    void narrativeGuard_blocksDominanceWithoutThreshold() {
        var stats = new StatisticalNarrativeGuard.NarrativeStats(10, 1.2, 0.05, 4);
        var result = narrativeGuard.guard("Segment A dominates revenue with strong impact.", stats);
        assertFalse(result.sanitizedText().toLowerCase().contains("dominates"));
        assertFalse(result.sanitizedText().toLowerCase().contains("strong impact"));
    }

    @Test
    void narrativeGuard_allowsDominanceWithThreshold() {
        var stats = new StatisticalNarrativeGuard.NarrativeStats(45, 8.5, 0.4, 6);
        var result = narrativeGuard.guard("Short trips dominate revenue.", stats);
        assertTrue(result.strongLanguageAllowed());
    }

    @Test
    void confidenceDecomposition_reflectsValidReport() {
        var materialized = SyntheticBenchmarkDatasets.revenueByTripDistance();
        var report = engine.verify(materialized, List.of(), GoldenAnalyticalTestSuite.all().getFirst());
        var conf = ConfidenceDecomposition.from(report, 1, 1, true);
        assertTrue(conf.composite() > 0.5);
        assertTrue(conf.aggregationConsistency() > 0.9);
    }
}
