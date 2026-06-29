package com.example.BACKEND.catalogue.decision.investigation;

import java.util.List;
import java.util.Map;

/**
 * The auditable evidence bundle for a CHANGE-mode dimension-driver investigation.
 * Every quantity is traceable to a warehouse query through {@link #provenanceMap()}.
 */
public record EvidencePack(
        Investigation investigation,
        MetricChange change,
        List<RankedDriver> drivers,
        List<RankedDriver> counterEvidence,
        Coverage coverage,
        Map<String, Provenance> provenanceMap,
        Confidence confidence
) {
    public record Investigation(
            String question,
            String mode,
            String metricColumn,
            String metricAggregation,
            String timeColumn,
            String grain,
            TimeWindow baselineWindow,
            TimeWindow observationWindow,
            String requestedDirection,
            List<String> dimensionsTested
    ) {}

    public record Coverage(
            double explainedPct,
            double residualPct,
            int dimensionsTested,
            int membersConsidered
    ) {}

    public record Provenance(
            String meaning,
            String sql,
            int rowCount
    ) {}

    public record Confidence(
            String level,
            String explanation,
            List<String> limitations
    ) {}
}
