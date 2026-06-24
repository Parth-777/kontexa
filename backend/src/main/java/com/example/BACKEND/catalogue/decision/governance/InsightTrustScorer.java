package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ConstitutionReview;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Insight trust scoring based on semantic validity, aggregation correctness,
 * denominator consistency, statistical strength, and sample adequacy.
 */
@Component
public class InsightTrustScorer {

    public TrustScore score(
            List<GroundedAnalyticalFinding> findings,
            List<GovernanceAudit> audits,
            EvidenceCoverageReport coverage,
            ConstitutionReview constitution,
            int rejectedCount,
            int totalCount
    ) {
        if (findings == null || findings.isEmpty()) {
            return new TrustScore(0.30, breakdownEmpty());
        }

        double semantic = average(audits, GovernanceAudit::semanticValidity);
        double aggregation = average(audits, GovernanceAudit::aggregationCorrectness);
        double denominator = average(audits, GovernanceAudit::denominatorConsistency);
        double statistical = average(audits, GovernanceAudit::statisticalStrength);
        double verification = average(audits, GovernanceAudit::verificationPassed);
        double consistency = average(audits, GovernanceAudit::consistencyPassed);

        double coverageFactor = coverage != null ? coverage.coverageScore() * 0.08 : 0.03;
        double constitutionFactor = constitution != null && constitution.totalClaimsReviewed() > 0
                ? Math.min(0.08, constitution.observations().size() * 0.015) : 0;
        double rejectionPenalty = totalCount > 0 ? (double) rejectedCount / totalCount * 0.20 : 0;

        double raw = semantic * 0.22
                + aggregation * 0.18
                + denominator * 0.15
                + statistical * 0.15
                + verification * 0.15
                + consistency * 0.10
                + coverageFactor
                + constitutionFactor
                - rejectionPenalty;

        double confidence = Math.min(0.92, Math.max(0.25, raw));
        return new TrustScore(confidence, new TrustBreakdown(
                semantic, aggregation, denominator, statistical, verification, consistency));
    }

    private double average(List<GovernanceAudit> audits, java.util.function.ToDoubleFunction<GovernanceAudit> fn) {
        if (audits == null || audits.isEmpty()) return 0.4;
        return audits.stream().mapToDouble(fn).average().orElse(0.4);
    }

    private TrustBreakdown breakdownEmpty() {
        return new TrustBreakdown(0, 0, 0, 0, 0, 0);
    }

    public record TrustScore(double confidence, TrustBreakdown breakdown) {}

    public record TrustBreakdown(
            double semanticValidity,
            double aggregationCorrectness,
            double denominatorConsistency,
            double statisticalStrength,
            double verificationPassed,
            double consistencyPassed
    ) {}

    public record GovernanceAudit(
            String findingType,
            boolean passed,
            double semanticValidity,
            double aggregationCorrectness,
            double denominatorConsistency,
            double statisticalStrength,
            double verificationPassed,
            double consistencyPassed,
            List<String> rejectReasons
    ) {
        static GovernanceAudit passed(String type, double statistical) {
            return new GovernanceAudit(type, true, 1, 1, 1, statistical, 1, 1, List.of());
        }

        static GovernanceAudit rejected(String type, List<String> reasons) {
            return new GovernanceAudit(type, false, 0.2, 0.2, 0.2, 0.2, 0, 0, reasons);
        }
    }
}
