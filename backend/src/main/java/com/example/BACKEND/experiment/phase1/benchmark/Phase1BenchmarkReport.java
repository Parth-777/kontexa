package com.example.BACKEND.experiment.phase1.benchmark;

import java.util.List;

public record Phase1BenchmarkReport(
        String pipelineName,
        int total,
        int metricCorrect,
        int dimensionCorrect,
        int filterCorrect,
        int sqlCorrect,
        int executionSuccess,
        List<Phase1CaseScore> cases
) {
    public static Phase1BenchmarkReport aggregate(String name, List<Phase1CaseScore> scores) {
        int n = scores.size();
        return new Phase1BenchmarkReport(
                name,
                n,
                (int) scores.stream().filter(s -> s.metricCorrect).count(),
                (int) scores.stream().filter(s -> s.dimensionCorrect).count(),
                (int) scores.stream().filter(s -> s.filterCorrect).count(),
                (int) scores.stream().filter(s -> s.sqlCorrect).count(),
                (int) scores.stream().filter(s -> s.executionSuccess).count(),
                scores);
    }

    public String summaryLine() {
        return String.format(
                "%s: metric=%d/%d dimension=%d/%d filter=%d/%d sql=%d/%d exec=%d/%d",
                pipelineName, metricCorrect, total, dimensionCorrect, total,
                filterCorrect, total, sqlCorrect, total, executionSuccess, total);
    }
}
