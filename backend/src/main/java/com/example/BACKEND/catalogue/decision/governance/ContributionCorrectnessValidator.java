package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding.Segment;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.ContributionScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validates contribution findings for mathematical and semantic correctness.
 */
@Component
public class ContributionCorrectnessValidator {

    private static final double SHARE_SUM_TOLERANCE = 5.0;
    private static final double SHARE_RECOMPUTE_TOLERANCE = 1.0;
    private static final double MAX_SINGLE_BUCKET_SHARE = 95.0;
    private static final int MIN_BUCKETS_FOR_GLOBAL = 3;

    private final MetricSemanticRegistry registry;

    public ContributionCorrectnessValidator(MetricSemanticRegistry registry) {
        this.registry = registry;
    }

    public ValidationResult validate(
            ContributionFinding finding,
            DenominatorContext denominator,
            double verifiedTotal
    ) {
        List<String> violations = new ArrayList<>();
        if (finding == null || finding.segments() == null || finding.segments().isEmpty()) {
            return reject("No segments in contribution finding");
        }

        var metricDef = registry.resolve(finding.metricLabel());
        if (metricDef.isPresent() && !metricDef.get().isFullyAdditive()) {
            violations.add("Contribution analysis requires fully-additive metric, got "
                    + metricDef.get().aggregationType());
        }

        double segmentValueSum = finding.segments().stream().mapToDouble(Segment::value).sum();
        double shareSum = finding.segments().stream().mapToDouble(Segment::sharePct).sum();

        if (segmentValueSum <= 0) violations.add("Non-positive segment value sum");
        if (verifiedTotal > 0 && Math.abs(segmentValueSum - verifiedTotal) / verifiedTotal > 0.05) {
            violations.add("Segment values do not reconcile with verified total");
        }

        for (Segment s : finding.segments()) {
            if (s.sharePct() < 0 || s.sharePct() > 100) {
                violations.add("Share out of range for segment: " + s.name());
            }
            double recomputed = segmentValueSum > 0 ? 100.0 * s.value() / segmentValueSum : 0;
            if (Math.abs(recomputed - s.sharePct()) > SHARE_RECOMPUTE_TOLERANCE) {
                violations.add("Share mismatch for " + s.name()
                        + ": stated=" + s.sharePct() + " recomputed=" + recomputed);
            }
        }

        if (shareSum > 0 && (shareSum < 100 - SHARE_SUM_TOLERANCE || shareSum > 100 + SHARE_SUM_TOLERANCE)) {
            violations.add("Bucket shares sum to " + String.format(Locale.ROOT, "%.1f", shareSum)
                    + "% — expected ~100% for global contribution");
        }

        ContributionScope scope = classifyScope(finding, denominator, shareSum);

        if (scope == ContributionScope.LOCAL && finding.executiveSummary() != null
                && finding.executiveSummary().toLowerCase(Locale.ROOT).contains("of total")) {
            violations.add("LOCAL contribution presented as global total share");
        }

        if (finding.topContributorSharePct() >= MAX_SINGLE_BUCKET_SHARE
                && finding.segments().size() < MIN_BUCKETS_FOR_GLOBAL) {
            violations.add("Suspicious near-100% claim: top bucket "
                    + finding.topContributorSharePct() + "% with only "
                    + finding.segments().size() + " buckets");
        }

        if (finding.topContributorSharePct() > 100) {
            violations.add("Contribution percentage exceeds 100%");
        }

        double recomputedTop3 = finding.segments().stream()
                .limit(3).mapToDouble(Segment::sharePct).sum();
        if (Math.abs(recomputedTop3 - finding.concentrationRatio()) > 2.0) {
            violations.add("Top-3 concentration does not match segment shares");
        }

        boolean valid = violations.isEmpty();
        return new ValidationResult(valid, violations, scope, segmentValueSum, shareSum);
    }

    private ContributionScope classifyScope(
            ContributionFinding finding, DenominatorContext denom, double shareSum
    ) {
        if (finding.segments().size() >= MIN_BUCKETS_FOR_GLOBAL
                && shareSum >= 100 - SHARE_SUM_TOLERANCE
                && shareSum <= 100 + SHARE_SUM_TOLERANCE) {
            return ContributionScope.GLOBAL;
        }
        return ContributionScope.LOCAL;
    }

    private ValidationResult reject(String reason) {
        return new ValidationResult(false, List.of(reason), ContributionScope.LOCAL, 0, 0);
    }

    public record ValidationResult(
            boolean valid,
            List<String> violations,
            ContributionScope scope,
            double verifiedSegmentSum,
            double verifiedShareSum
    ) {}
}
