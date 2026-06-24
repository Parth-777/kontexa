package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts raw, unclassified claims from computed evidence.
 *
 * Sources of claims:
 *   1. Metric values         — direct numeric facts from warehouse execution
 *   2. Comparative contexts  — period-over-period, vs-baseline, vs-peer
 *   3. Investigation tree    — segment contribution and concentration findings
 *
 * Output is a list of {@link RawClaim} ready for epistemic classification.
 * Claims at this stage carry no epistemic label — they are extracted as-is.
 */
@Component
public class ClaimExtractor {

    /**
     * A pre-classification claim extracted from evidence.
     * Source determines how it will be classified.
     */
    public enum ClaimSource {
        METRIC_VALUE,
        COMPARATIVE_CONTEXT,
        INVESTIGATION_FINDING,
        INVESTIGATION_INTERPRETATION
    }

    public record RawClaim(
            String      claimText,
            ClaimSource source,
            double      magnitude,
            String      evidenceRef
    ) {}

    public List<RawClaim> extract(List<RankedEvidence> ranked, List<EvidenceObject> evidence) {
        List<RawClaim> claims = new ArrayList<>();

        for (RankedEvidence re : ranked) {
            EvidenceObject ev = findEvidence(evidence, re.evidenceId());
            if (ev == null) continue;

            // 1. Top-line metric facts
            ev.metrics().entrySet().stream().limit(4).forEach(entry -> {
                Object val = entry.getValue();
                double mag = numericValue(val);
                if (mag > 0) {
                    claims.add(new RawClaim(
                            formatMetricClaim(entry.getKey(), val),
                            ClaimSource.METRIC_VALUE,
                            mag,
                            ev.evidenceId()
                    ));
                }
            });

            // 2. Comparative contexts — directional change claims
            for (ComparativeContext ctx : ev.comparativeContexts()) {
                claims.add(new RawClaim(
                        formatComparativeClaim(ctx),
                        ClaimSource.COMPARATIVE_CONTEXT,
                        Math.abs(ctx.deltaPercent()),
                        ev.evidenceId()
                ));
            }

            // 3. Investigation tree — segment contribution (data-backed)
            for (InvestigationNode node : ev.investigationTree()) {
                claims.add(new RawClaim(
                        node.signal(),
                        ClaimSource.INVESTIGATION_FINDING,
                        node.impactScore(),
                        ev.evidenceId()
                ));
                // Sub-interpretations — analytical text, not raw data
                if (!node.interpretation().isBlank()) {
                    claims.add(new RawClaim(
                            node.interpretation(),
                            ClaimSource.INVESTIGATION_INTERPRETATION,
                            node.impactScore(),
                            ev.evidenceId()
                    ));
                }
            }
        }

        return claims;
    }

    // ─── formatters ────────────────────────────────────────────────────

    private String formatMetricClaim(String key, Object value) {
        String shortKey = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        return shortKey.replace("_", " ") + " = " + formatValue(value);
    }

    private String formatComparativeClaim(ComparativeContext ctx) {
        String metric = ctx.metricKey().contains(".")
                ? ctx.metricKey().substring(ctx.metricKey().lastIndexOf('.') + 1).replace("_", " ")
                : ctx.metricKey();
        String type = ctx.comparisonType().name().replace("_", " ").toLowerCase();
        String dir  = ctx.directionLabel().toUpperCase();
        return String.format("%s is %s %.1f%% %s vs %s [%s → %s]",
                metric, dir, Math.abs(ctx.deltaPercent()), type,
                ctx.referencePeriod(), ctx.referencePeriod(), ctx.currentPeriod());
    }

    private String formatValue(Object v) {
        if (v == null) return "null";
        try { return String.format("%.2f", Double.parseDouble(v.toString())); }
        catch (Exception e) { return v.toString(); }
    }

    private double numericValue(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); }
        catch (Exception e) { return 0; }
    }

    private EvidenceObject findEvidence(List<EvidenceObject> evidence, String evidenceId) {
        return evidence.stream().filter(ev -> ev.evidenceId().equals(evidenceId)).findFirst().orElse(null);
    }
}
