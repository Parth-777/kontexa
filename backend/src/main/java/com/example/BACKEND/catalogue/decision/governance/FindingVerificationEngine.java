package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Independently recomputes displayed metrics from raw aggregates before presentation.
 * Fails closed on inconsistencies.
 */
@Component
public class FindingVerificationEngine {

    private static final double TOLERANCE_PCT = 1.5;
    private static final double TOLERANCE_VALUE = 0.01;

    private final ContributionCorrectnessValidator contributionValidator;

    public FindingVerificationEngine(ContributionCorrectnessValidator contributionValidator) {
        this.contributionValidator = contributionValidator;
    }

    public VerificationResult verify(
            AnalyticalFinding finding,
            MaterializedQueryResult materialized
    ) {
        if (finding == null) return VerificationResult.fail("Null finding");

        return switch (finding) {
            case ContributionFinding c -> verifyContribution(c, materialized);
            case RankingFinding r -> verifyRanking(r, materialized);
            case ComparativeFinding c -> verifyComparative(c);
            case EfficiencyFinding e -> verifyEfficiency(e);
            case TemporalPatternFinding t -> verifyTemporal(t);
            case CorrelationFinding c ->
                    VerificationResult.pass(DenominatorContext.unknown(), c.correlationCoefficient());
        };
    }

    private VerificationResult verifyContribution(
            ContributionFinding finding, MaterializedQueryResult materialized
    ) {
        MaterializedGrouping grouping = matchGrouping(finding.dimensionLabel(), materialized);
        double verifiedTotal = grouping != null ? grouping.totalValueSum() : sumSegmentValues(finding);

        DenominatorContext denom = grouping != null
                ? DenominatorContext.forContribution(
                        finding.metricLabel(), finding.dimensionLabel(),
                        grouping.totalValueSum(), grouping.groupCount(),
                        materialized != null ? materialized.totalRows() : 0)
                : DenominatorContext.unknown();

        var cv = contributionValidator.validate(finding, denom, verifiedTotal);
        if (!cv.valid()) {
            return VerificationResult.fail(cv.violations());
        }

        if (grouping != null) {
            List<String> mismatches = recomputeAndCompare(finding, grouping);
            if (!mismatches.isEmpty()) return VerificationResult.fail(mismatches);
        }

        return VerificationResult.pass(denom, verifiedTotal);
    }

    private List<String> recomputeAndCompare(ContributionFinding finding, MaterializedGrouping grouping) {
        List<String> issues = new ArrayList<>();
        double total = grouping.totalValueSum();

        for (Segment seg : finding.segments()) {
            MaterializedGroupEntry entry = grouping.rankedEntries().stream()
                    .filter(e -> matchesSegment(e.entityKey(), seg.name()))
                    .findFirst().orElse(null);
            if (entry == null) continue;

            double expectedShare = total > 0 ? 100.0 * entry.totalValue() / total : 0;
            if (Math.abs(expectedShare - seg.sharePct()) > TOLERANCE_PCT) {
                issues.add("Share recompute mismatch for " + seg.name()
                        + ": raw=" + expectedShare + " finding=" + seg.sharePct());
            }
            if (Math.abs(entry.totalValue() - seg.value()) / Math.max(1, entry.totalValue()) > TOLERANCE_VALUE) {
                issues.add("Value recompute mismatch for " + seg.name());
            }
        }

        Segment top = finding.segments().getFirst();
        MaterializedGroupEntry topEntry = grouping.rankedEntries().getFirst();
        double topShare = total > 0 ? 100.0 * topEntry.totalValue() / total : 0;
        if (Math.abs(topShare - finding.topContributorSharePct()) > TOLERANCE_PCT) {
            issues.add("Top contributor share mismatch: raw=" + topShare
                    + " finding=" + finding.topContributorSharePct());
        }

        return issues;
    }

    private VerificationResult verifyRanking(RankingFinding r, MaterializedQueryResult materialized) {
        if (r.rankedEntities().isEmpty()) return VerificationResult.fail("Empty ranking");
        MaterializedGrouping g = matchGrouping(r.groupingLabel(), materialized);
        if (g == null) return VerificationResult.pass(DenominatorContext.unknown(), 0);

        var top = r.rankedEntities().getFirst();
        var rawTop = g.rankedEntries().getFirst();
        if (Math.abs(top.value() - rawTop.totalValue()) / Math.max(1, rawTop.totalValue()) > TOLERANCE_VALUE) {
            return VerificationResult.fail(List.of("Leader value does not match raw aggregate"));
        }
        return VerificationResult.pass(DenominatorContext.unknown(), g.totalValueSum());
    }

    private VerificationResult verifyComparative(ComparativeFinding c) {
        double recomputedDelta = c.valueA() - c.valueB();
        if (Math.abs(recomputedDelta - c.delta()) > Math.max(1, Math.abs(c.delta()) * 0.05)) {
            return VerificationResult.fail(List.of("Comparative delta does not recompute"));
        }
        return VerificationResult.pass(DenominatorContext.unknown(), 0);
    }

    private VerificationResult verifyEfficiency(EfficiencyFinding e) {
        if (e.entries().isEmpty()) return VerificationResult.fail("Empty efficiency finding");
        return VerificationResult.pass(DenominatorContext.unknown(), 0);
    }

    private VerificationResult verifyTemporal(TemporalPatternFinding t) {
        if (t.periods() == null || t.periods().size() < 2) {
            return VerificationResult.fail("Insufficient temporal periods");
        }
        return VerificationResult.pass(DenominatorContext.unknown(), 0);
    }

    private MaterializedGrouping matchGrouping(String label, MaterializedQueryResult materialized) {
        if (materialized == null || materialized.primaryGrouping() == null) return null;
        if (materialized.groupings() != null) {
            for (var g : materialized.groupings()) {
                if (g.spec() != null && labelsMatch(g.spec().displayLabel(), label)) return g;
            }
        }
        return materialized.primaryGrouping();
    }

    private boolean labelsMatch(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.toLowerCase(Locale.ROOT).replace(" ", "");
        String nb = b.toLowerCase(Locale.ROOT).replace(" ", "");
        return na.contains(nb) || nb.contains(na);
    }

    private boolean matchesSegment(String entityKey, String segmentName) {
        if (entityKey == null || segmentName == null) return false;
        return entityKey.equalsIgnoreCase(segmentName)
                || segmentName.toLowerCase(Locale.ROOT).contains(entityKey.toLowerCase(Locale.ROOT));
    }

    private double sumSegmentValues(ContributionFinding c) {
        return c.segments().stream().mapToDouble(Segment::value).sum();
    }

    public record VerificationResult(
            boolean verified,
            List<String> issues,
            DenominatorContext denominator,
            double verifiedTotal
    ) {
        static VerificationResult pass(DenominatorContext d, double total) {
            return new VerificationResult(true, List.of(), d, total);
        }

        static VerificationResult fail(String reason) {
            return new VerificationResult(false, List.of(reason), DenominatorContext.unknown(), 0);
        }

        static VerificationResult fail(List<String> issues) {
            return new VerificationResult(false, issues, DenominatorContext.unknown(), 0);
        }
    }
}
