package com.example.BACKEND.catalogue.decision.grounding;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Confidence based on semantic validity — not query execution alone.
 */
@Component
public class SemanticConfidenceScorer {

    private final SemanticRelationshipValidator validator;

    public SemanticConfidenceScorer(SemanticRelationshipValidator validator) {
        this.validator = validator;
    }

    public double score(
            List<AnalyticalFinding> groundedFindings,
            StructuredFindingsBundle originalBundle,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            int rejectedObservations,
            int totalObservations
    ) {
        if (groundedFindings == null || groundedFindings.isEmpty()) {
            return 0.35;
        }

        double semanticValidity = averageFindingValidity(groundedFindings);
        double grounding = metricGroundingScore(groundedFindings);
        double aggregation = aggregationConsistency(originalBundle);
        double dimensional = dimensionalCoherence(groundedFindings);
        double coverageFactor = coverage != null ? coverage.coverageScore() * 0.15 : 0.05;
        double constitutionFactor = constitution != null && constitution.totalClaimsReviewed() > 0
                ? Math.min(0.12, constitution.observations().size() * 0.02)
                : 0.0;

        double rejectionPenalty = totalObservations > 0
                ? (double) rejectedObservations / totalObservations * 0.25
                : 0.0;

        double raw = semanticValidity * 0.35
                + grounding * 0.25
                + aggregation * 0.15
                + dimensional * 0.15
                + coverageFactor
                + constitutionFactor
                - rejectionPenalty;

        return Math.min(0.92, Math.max(0.30, raw));
    }

    private double averageFindingValidity(List<AnalyticalFinding> findings) {
        if (findings.isEmpty()) return 0.0;
        double sum = 0;
        for (AnalyticalFinding f : findings) {
            var vr = validator.validateFinding(f);
            sum += vr.valid() ? 1.0 : Math.max(0.3, 1.0 - vr.violations().size() * 0.2);
        }
        return sum / findings.size();
    }

    private double metricGroundingScore(List<AnalyticalFinding> findings) {
        long withMetric = findings.stream()
                .filter(f -> switch (f) {
                    case AnalyticalFinding.ContributionFinding c ->
                            c.metricLabel() != null && !c.metricLabel().isBlank();
                    case AnalyticalFinding.RankingFinding r ->
                            r.metricLabel() != null && !r.metricLabel().isBlank();
                    default -> true;
                })
                .count();
        return findings.isEmpty() ? 0 : (double) withMetric / findings.size();
    }

    private double aggregationConsistency(StructuredFindingsBundle bundle) {
        if (bundle == null || !bundle.hasStructuredFindings()) return 0.4;
        int total = bundle.totalFindingCount();
        if (total == 0) return 0.4;
        if (total >= 2 && bundle.primaryFindingType() != null) return 0.85;
        return 0.65;
    }

    private double dimensionalCoherence(List<AnalyticalFinding> findings) {
        long coherent = findings.stream()
                .filter(f -> {
                    if (f instanceof AnalyticalFinding.ContributionFinding c) {
                        return validator.isMetricDimensionCompatible(
                                c.metricLabel(), c.dimensionLabel());
                    }
                    return true;
                })
                .count();
        return findings.isEmpty() ? 0 : (double) coherent / findings.size();
    }
}
