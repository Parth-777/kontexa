package com.example.BACKEND.catalogue.decision.verification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proves analytical correctness from SQL/grouped results — not LLM narrative.
 */
@Component
public class AnalyticalVerificationEngine {

    private static final double RECONCILE_TOLERANCE = 0.05;
    private static final double MIN_VARIANCE_CV    = 0.08;
    private static final int    MIN_GROUPS         = 2;

    public record VerificationReport(
            boolean          passed,
            List<String>     violations,
            double           groupedTotal,
            double           overallTotal,
            double           reconcileDeltaPct,
            double           shareSum,
            double           coefficientOfVariation,
            int              groupCount,
            double           leaderSharePct,
            double           leaderToTailMultiple,
            GoldenAnalyticalBenchmark benchmarkMatch
    ) {
        public static VerificationReport fail(List<String> issues) {
            return new VerificationReport(false, issues, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    public VerificationReport verify(
            MaterializedQueryResult materialized,
            List<QueryResult> queryResults,
            GoldenAnalyticalBenchmark benchmark
    ) {
        List<String> violations = new ArrayList<>();

        boolean compositionMode = benchmark != null
                && benchmark.concentrationPattern() == GoldenAnalyticalBenchmark.ConcentrationPattern.TIP_COMPOSITION;

        if (materialized == null || materialized.primaryGrouping() == null) {
            violations.add("No grouped materialized result");
            return VerificationReport.fail(violations);
        }
        if (!compositionMode && !materialized.hasContent()) {
            violations.add("No grouped materialized result");
            return VerificationReport.fail(violations);
        }

        MaterializedGrouping g = materialized.primaryGrouping();
        int minGroups = compositionMode ? 1 : MIN_GROUPS;
        if (g.rankedEntries() == null || g.rankedEntries().size() < minGroups) {
            violations.add("Fewer than " + minGroups + " grouped rows");
        }

        double groupedSum = g.rankedEntries().stream().mapToDouble(MaterializedGroupEntry::totalValue).sum();
        double overall = g.totalValueSum() > 0 ? g.totalValueSum() : groupedSum;
        double reconcileDelta = overall > 0 ? Math.abs(groupedSum - overall) / overall : 0;

        if (overall <= 0) violations.add("Non-positive aggregation total");
        if (reconcileDelta > RECONCILE_TOLERANCE) {
            violations.add(String.format(Locale.ROOT,
                    "Grouped totals do not reconcile: sum=%.2f overall=%.2f delta=%.1f%%",
                    groupedSum, overall, reconcileDelta * 100));
        }

        double shareSum = g.rankedEntries().stream().mapToDouble(MaterializedGroupEntry::sharePct).sum();
        for (MaterializedGroupEntry e : g.rankedEntries()) {
            if (Double.isNaN(e.totalValue()) || e.totalValue() < 0) {
                violations.add("Null or negative metric for segment: " + e.entityKey());
            }
            if (e.sharePct() < -0.01 || e.sharePct() > 100.01) {
                violations.add("Impossible share for " + e.entityKey() + ": " + e.sharePct());
            }
        }
        if (!compositionMode && g.groupCount() >= MIN_GROUPS && (shareSum < 95 || shareSum > 105)) {
            violations.add("Share percentages sum to " + String.format(Locale.ROOT, "%.1f", shareSum));
        }

        double cv = coefficientOfVariation(g.rankedEntries());
        if (!compositionMode && g.groupCount() >= MIN_GROUPS && cv < MIN_VARIANCE_CV) {
            violations.add("Insufficient variance across groups (cv=" + String.format(Locale.ROOT, "%.3f", cv) + ")");
        }

        violations.addAll(verifySqlRows(queryResults));
        if (benchmark != null) {
            violations.addAll(verifyBenchmark(g, benchmark));
        }

        var entries = g.rankedEntries();
        double leaderShare = entries.isEmpty() ? 0 : entries.getFirst().sharePct();
        double tailVal = entries.isEmpty() ? 0 : entries.getLast().totalValue();
        double leaderMultiple = tailVal > 0 ? entries.getFirst().totalValue() / tailVal : 0;

        return new VerificationReport(
                violations.isEmpty(),
                violations,
                groupedSum,
                overall,
                reconcileDelta * 100,
                shareSum,
                cv,
                g.groupCount(),
                leaderShare,
                leaderMultiple,
                benchmark
        );
    }

    public double verifyMetricReconciliation(List<Map<String, Object>> groupedRows, String valueKey) {
        if (groupedRows == null || groupedRows.isEmpty()) return 0;
        return groupedRows.stream()
                .mapToDouble(r -> toDouble(r.get(valueKey)))
                .sum();
    }

    private List<String> verifySqlRows(List<QueryResult> results) {
        List<String> issues = new ArrayList<>();
        if (results == null) return issues;
        for (QueryResult qr : results) {
            if (qr.rows() == null || qr.rows().isEmpty()) continue;
            if (qr.rows().size() == 1 && qr.rows().getFirst().containsKey("tip_share_pct")) {
                continue; // composition query — single row expected
            }
            if (qr.rows().size() < MIN_GROUPS) {
                issues.add("SQL result " + qr.key() + " returned fewer than " + MIN_GROUPS + " rows");
            }
        }
        return issues;
    }

    private List<String> verifyBenchmark(MaterializedGrouping g, GoldenAnalyticalBenchmark benchmark) {
        List<String> issues = new ArrayList<>();
        if (g.groupCount() < benchmark.minGroupCount()) {
            issues.add("Benchmark requires at least " + benchmark.minGroupCount() + " groups");
        }

        var entries = g.rankedEntries();
        boolean composition = benchmark.concentrationPattern()
                == GoldenAnalyticalBenchmark.ConcentrationPattern.TIP_COMPOSITION;
        if (!composition && !entries.isEmpty() && benchmark.expectedLeaderSharePct() != null) {
            double topShare = entries.getFirst().sharePct();
            if (!benchmark.expectedLeaderSharePct().contains(topShare)) {
                issues.add("Leader share " + topShare + "% outside expected range");
            }
        }

        if (!composition && entries.size() >= 2 && benchmark.expectedLeaderToTailMultiple() != null) {
            double tail = entries.getLast().totalValue();
            double multiple = tail > 0 ? entries.getFirst().totalValue() / tail : 0;
            if (!benchmark.expectedLeaderToTailMultiple().contains(multiple)) {
                issues.add("Leader-to-tail multiple " + multiple + " outside expected range");
            }
        }

        if (!composition && !benchmark.expectedOrdering().isEmpty() && !entries.isEmpty()) {
            String actualLeader = entries.getFirst().entityKey();
            String expectedLeader = benchmark.expectedOrdering().getFirst();
            if (!actualLeader.contains(expectedLeader) && !expectedLeader.contains(actualLeader)) {
                issues.add("Expected leader " + expectedLeader + " but got " + actualLeader);
            }
        }
        if (composition) {
            double tipShare = entries.stream()
                    .filter(e -> e.entityKey().toLowerCase(Locale.ROOT).contains("tip"))
                    .mapToDouble(MaterializedGroupEntry::sharePct)
                    .findFirst().orElse(-1);
            if (tipShare < 0) {
                issues.add("No tips segment found in composition result");
            } else if (benchmark.expectedLeaderSharePct() != null
                    && !benchmark.expectedLeaderSharePct().contains(tipShare)) {
                issues.add("Tip share " + tipShare + "% outside expected range");
            }
        }
        return issues;
    }

    private double coefficientOfVariation(List<MaterializedGroupEntry> entries) {
        if (entries == null || entries.size() < 2) return 0;
        double[] vals = entries.stream().mapToDouble(MaterializedGroupEntry::totalValue).toArray();
        double mean = 0;
        for (double v : vals) mean += v;
        mean /= vals.length;
        if (mean <= 0) return 0;
        double var = 0;
        for (double v : vals) var += (v - mean) * (v - mean);
        return Math.sqrt(var / vals.length) / mean;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
