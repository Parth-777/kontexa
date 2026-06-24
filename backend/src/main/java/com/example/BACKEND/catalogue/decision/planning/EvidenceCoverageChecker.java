package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EvidenceObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates whether assembled evidence is sufficient to support
 * the conclusions required by the {@link InvestigationPlan}.
 *
 * Coverage is assessed by matching the plan's metric requirements against
 * the keys present in assembled evidence objects.
 *
 * If evidence is weak:
 *   - Confidence is reduced
 *   - Synthesis is instructed to avoid strong conclusions
 *   - Missing dimensions are explicitly flagged in the prompt
 *
 * This prevents the system from generating confident-sounding outputs
 * when the underlying data is thin.
 */
@Component
public class EvidenceCoverageChecker {

    private static final double SUFFICIENT_THRESHOLD = 0.6;

    public EvidenceCoverageReport check(
            InvestigationPlan    plan,
            List<EvidenceObject> evidence
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return EvidenceCoverageReport.insufficient(
                    plan.metricRequirements().stream()
                            .filter(m -> m.priority() == 1)
                            .map(MetricRequirement::metricKey)
                            .toList()
            );
        }

        // Build the set of all metric keys present in assembled evidence
        Set<String> coveredKeys = extractCoveredKeys(evidence);

        // Evaluate critical requirements first (priority=1)
        List<MetricRequirement> criticalReqs = plan.metricRequirements().stream()
                .filter(r -> r.priority() == 1).toList();
        List<MetricRequirement> allReqs = plan.metricRequirements();

        List<String> missingCritical = new ArrayList<>();
        List<String> missingAll      = new ArrayList<>();

        for (MetricRequirement req : criticalReqs) {
            if (!isCovered(req.metricKey(), coveredKeys)) {
                missingCritical.add(req.metricKey() + " (" + req.analyticalPurpose() + ")");
            }
        }

        for (MetricRequirement req : allReqs) {
            if (!isCovered(req.metricKey(), coveredKeys)) {
                missingAll.add(req.metricKey());
            }
        }

        int totalReqs = allReqs.size();
        int covered   = totalReqs - missingAll.size();
        double score  = totalReqs == 0 ? 1.0 : (double) covered / totalReqs;

        // Any missing critical requirement degrades confidence sharply
        if (!missingCritical.isEmpty()) {
            double adjustedScore = Math.max(0.2, score - (missingCritical.size() * 0.15));
            return EvidenceCoverageReport.partial(adjustedScore, missingCritical);
        }

        if (score >= SUFFICIENT_THRESHOLD) {
            if (missingAll.isEmpty()) return EvidenceCoverageReport.full();
            return EvidenceCoverageReport.partial(score, missingAll.subList(0, Math.min(3, missingAll.size())));
        }

        return EvidenceCoverageReport.insufficient(missingCritical.isEmpty() ? missingAll : missingCritical);
    }

    // ─── coverage helpers ────────────────────────────────────────────────

    /**
     * Semantic matching: a metric requirement is "covered" if any evidence key
     * contains the requirement's key as a substring (semantic overlap).
     * e.g. "total_value" covers "trip_total_value", "revenue_total_value", etc.
     */
    private boolean isCovered(String requiredKey, Set<String> coveredKeys) {
        String norm = requiredKey.toLowerCase();
        for (String k : coveredKeys) {
            if (k.contains(norm) || norm.contains(k)) return true;
            // Semantic overlap check via key segment matching
            if (sharedSegments(norm, k.toLowerCase()) >= 1) return true;
        }
        return false;
    }

    private int sharedSegments(String a, String b) {
        String[] segsA = a.split("[_.]");
        String[] segsB = b.split("[_.]");
        int shared = 0;
        for (String sa : segsA) {
            if (sa.length() < 3) continue;
            for (String sb : segsB) {
                if (sa.equals(sb)) shared++;
            }
        }
        return shared;
    }

    private Set<String> extractCoveredKeys(List<EvidenceObject> evidence) {
        return evidence.stream()
                .flatMap(ev -> {
                    List<String> keys = new ArrayList<>(ev.metrics().keySet());
                    keys.addAll(ev.comparisons().keySet());
                    ev.comparativeContexts().forEach(ctx -> keys.add(ctx.metricKey()));
                    return keys.stream();
                })
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }
}
