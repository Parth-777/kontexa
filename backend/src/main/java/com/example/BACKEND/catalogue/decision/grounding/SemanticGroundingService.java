package com.example.BACKEND.catalogue.decision.grounding;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.presentation.NarrativeCompressor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates semantic grounding: label resolution, validation, observation filtering,
 * and business-valid narrative correction before presentation.
 */
@Service
public class SemanticGroundingService {

    private final ObservationFilter observationFilter;
    private final SemanticRelationshipValidator validator;
    private final SemanticConfidenceScorer confidenceScorer;
    private final PresentationLabelResolver labels;
    private final NarrativeCompressor narrativeCompressor;

    public SemanticGroundingService(
            ObservationFilter observationFilter,
            SemanticRelationshipValidator validator,
            SemanticConfidenceScorer confidenceScorer,
            PresentationLabelResolver labels,
            NarrativeCompressor narrativeCompressor
    ) {
        this.observationFilter = observationFilter;
        this.validator = validator;
        this.confidenceScorer = confidenceScorer;
        this.labels = labels;
        this.narrativeCompressor = narrativeCompressor;
    }

    /**
     * Ground findings before synthesis — ensures LLM receives business-valid semantics.
     */
    public StructuredFindingsBundle groundFindingsBundle(StructuredFindingsBundle bundle) {
        if (bundle == null || !bundle.hasStructuredFindings()) return StructuredFindingsBundle.empty();
        List<AnalyticalFinding> grounded = observationFilter.filterFindings(bundle.allFindings());
        return StructuredFindingsBundle.fromGroundedFindings(grounded, bundle.primaryFindingType());
    }

    public GroundingResult ground(
            StructuredFindingsBundle bundle,
            InsightOutput insight,
            List<EvidenceObject> evidence,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage
    ) {
        List<AnalyticalFinding> rawFindings = bundle != null
                ? bundle.allFindings()
                : List.of();

        int totalObs = countObservations(rawFindings, evidence);
        List<AnalyticalFinding> groundedFindings = observationFilter.filterFindings(rawFindings);
        List<EvidenceObject> filteredEvidence = observationFilter.filterEvidence(evidence);
        int rejected = Math.max(0, totalObs - groundedFindings.size()
                - filteredEvidence.stream().mapToInt(e -> e.metrics() != null ? e.metrics().size() : 0).sum());

        InsightOutput groundedInsight = groundInsight(insight, groundedFindings);

        double confidence = confidenceScorer.score(
                groundedFindings, bundle, constitution, coverage, rejected, totalObs);

        return new GroundingResult(
                groundedFindings,
                filteredEvidence,
                groundedInsight,
                confidence,
                rejected
        );
    }

    private InsightOutput groundInsight(InsightOutput insight, List<AnalyticalFinding> findings) {
        if (insight == null) return null;

        String title = labels.resolve(insight.title());
        String narrative = narrativeCompressor.compress(
                narrativeCompressor.clean(insight.narrative()), 3);

        List<String> narrativeViolations = validator.validateNarrative(narrative, null);
        if (!narrativeViolations.isEmpty() && !findings.isEmpty()) {
            narrative = validator.validateFinding(findings.getFirst()).correctedSummary();
        }

        List<String> actions = insight.actions() != null
                ? insight.actions().stream().map(labels::resolve).limit(3).toList()
                : List.of();

        return new InsightOutput(
                insight.insightId(),
                title,
                narrative,
                actions,
                insight.evidenceRefs(),
                filterStringList(insight.strategicImplications()),
                filterStringList(insight.operationalRisks()),
                filterStringList(insight.businessCauses()),
                labels.resolve(insight.prioritizationRationale())
        );
    }

    private List<String> filterStringList(List<String> items) {
        if (items == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (item == null || item.isBlank()) continue;
            if (validator.validateNarrative(item, null).isEmpty()) {
                out.add(narrativeCompressor.clean(labels.resolve(item)));
            }
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private String findingSummary(AnalyticalFinding f) {
        return switch (f) {
            case AnalyticalFinding.ContributionFinding c -> c.executiveSummary();
            case AnalyticalFinding.RankingFinding r -> r.executiveSummary();
            case AnalyticalFinding.EfficiencyFinding e -> e.executiveSummary();
            case AnalyticalFinding.TemporalPatternFinding t -> t.executiveSummary();
            case AnalyticalFinding.ComparativeFinding c -> c.executiveSummary();
            case AnalyticalFinding.CorrelationFinding c -> c.executiveSummary();
        };
    }

    private int countObservations(List<AnalyticalFinding> findings, List<EvidenceObject> evidence) {
        int n = findings.size();
        if (evidence != null) {
            for (EvidenceObject ev : evidence) {
                if (ev.metrics() != null) n += ev.metrics().size();
            }
        }
        return Math.max(1, n);
    }

    public record GroundingResult(
            List<AnalyticalFinding> groundedFindings,
            List<EvidenceObject> filteredEvidence,
            InsightOutput groundedInsight,
            double semanticConfidence,
            int rejectedObservations
    ) {}
}
