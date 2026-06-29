package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the auditable {@link EvidencePack} from the confirmed change, ranked drivers,
 * and query provenance.
 */
@Component
public class EvidencePackAssembler {

    public EvidencePack assemble(
            InvestigationSpec spec,
            MetricChange change,
            DriverRanker.Result ranking,
            List<DimensionEvidence> evidence,
            List<QuerySpec> specs,
            List<QueryResult> results,
            Map<String, String> meanings
    ) {
        List<String> dimensionsTested = spec.candidateDimensions().stream()
                .map(CandidateDimension::column)
                .toList();
        int membersConsidered = evidence.stream().mapToInt(DimensionEvidence::memberCount).sum();

        EvidencePack.Investigation investigation = new EvidencePack.Investigation(
                spec.question(),
                "CHANGE",
                spec.targetMeasure().column(),
                spec.targetMeasure().aggregation(),
                spec.timeColumn(),
                spec.grain(),
                spec.baselineWindow(),
                spec.observationWindow(),
                spec.direction(),
                dimensionsTested);

        EvidencePack.Coverage coverage = new EvidencePack.Coverage(
                round(ranking.explainedPct()),
                round(ranking.residualPct()),
                evidence.size(),
                membersConsidered);

        Map<String, EvidencePack.Provenance> provenance = buildProvenance(specs, results, meanings);
        EvidencePack.Confidence confidence = buildConfidence(ranking.explainedPct());

        return new EvidencePack(
                investigation,
                change,
                ranking.rankedDrivers(),
                ranking.counterEvidence(),
                coverage,
                provenance,
                confidence);
    }

    private static Map<String, EvidencePack.Provenance> buildProvenance(
            List<QuerySpec> specs, List<QueryResult> results, Map<String, String> meanings
    ) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        for (QueryResult r : results) {
            if (r.key() != null) {
                rowCounts.put(r.key(), r.rows() != null ? r.rows().size() : 0);
            }
        }
        Map<String, EvidencePack.Provenance> provenance = new LinkedHashMap<>();
        for (QuerySpec s : specs) {
            String meaning = meanings.getOrDefault(s.key(), s.key());
            int rowCount = rowCounts.getOrDefault(s.key(), 0);
            provenance.put(s.key(), new EvidencePack.Provenance(meaning, s.sql(), rowCount));
        }
        return provenance;
    }

    private static EvidencePack.Confidence buildConfidence(double explainedPct) {
        String level;
        if (explainedPct >= 80.0) {
            level = "HIGH";
        } else if (explainedPct >= 50.0) {
            level = "MEDIUM";
        } else {
            level = "LOW";
        }
        String explanation = String.format(
                "Ranked dimension drivers account for %.1f%% of the headline change.", explainedPct);
        List<String> limitations = new ArrayList<>(List.of(
                "dimension-drivers-only",
                "additive-measure",
                "single-period-comparison"));
        return new EvidencePack.Confidence(level, explanation, limitations);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
