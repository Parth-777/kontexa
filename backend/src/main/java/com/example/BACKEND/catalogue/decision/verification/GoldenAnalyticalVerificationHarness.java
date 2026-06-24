package com.example.BACKEND.catalogue.decision.verification;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs mandatory golden benchmarks against synthetic known-answer datasets.
 */
@Component
public class GoldenAnalyticalVerificationHarness {

    private final AnalyticalVerificationEngine engine;

    public GoldenAnalyticalVerificationHarness(AnalyticalVerificationEngine engine) {
        this.engine = engine;
    }

    public record BenchmarkResult(
            String  benchmarkId,
            String  question,
            boolean passed,
            List<String> violations
    ) {}

    public record HarnessReport(
            int passed,
            int failed,
            List<BenchmarkResult> results
    ) {
        public boolean allPassed() { return failed == 0; }
    }

    public HarnessReport runAll() {
        List<BenchmarkResult> results = new ArrayList<>();
        int passed = 0, failed = 0;
        for (GoldenAnalyticalBenchmark benchmark : GoldenAnalyticalTestSuite.all()) {
            var materialized = SyntheticBenchmarkDatasets.forBenchmark(benchmark);
            var report = engine.verify(materialized, List.of(), benchmark);
            if (report.passed()) passed++;
            else failed++;
            results.add(new BenchmarkResult(
                    benchmark.id(), benchmark.question(), report.passed(), report.violations()));
        }
        return new HarnessReport(passed, failed, results);
    }

    public Map<String, Object> toMap(HarnessReport report) {
        return Map.of(
                "passed", report.passed(),
                "failed", report.failed(),
                "all_passed", report.allPassed(),
                "results", report.results().stream().map(r -> Map.of(
                        "id", r.benchmarkId(),
                        "question", r.question(),
                        "passed", r.passed(),
                        "violations", r.violations()
                )).toList()
        );
    }
}
