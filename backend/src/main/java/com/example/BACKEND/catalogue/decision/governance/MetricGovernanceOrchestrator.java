package com.example.BACKEND.catalogue.decision.governance;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ConstitutionReview;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.findings.FindingType;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.governance.InsightTrustScorer.GovernanceAudit;
import com.example.BACKEND.catalogue.decision.governance.InsightTrustScorer.TrustScore;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator.ReasoningResult;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic metric governance layer — rejects invalid findings before synthesis,
 * verifies before presentation, and scores insight trust.
 */
@Service
public class MetricGovernanceOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MetricGovernanceOrchestrator.class);

    private final MetricSemanticRegistry registry;
    private final ContributionCorrectnessValidator contributionValidator;
    private final AggregationValidityEngine aggregationEngine;
    private final StatisticalAdequacyGuard adequacyGuard;
    private final FindingVerificationEngine verificationEngine;
    private final AnalyticalConsistencyChecker consistencyChecker;
    private final InsightTrustScorer trustScorer;

    public MetricGovernanceOrchestrator(
            MetricSemanticRegistry registry,
            ContributionCorrectnessValidator contributionValidator,
            AggregationValidityEngine aggregationEngine,
            StatisticalAdequacyGuard adequacyGuard,
            FindingVerificationEngine verificationEngine,
            AnalyticalConsistencyChecker consistencyChecker,
            InsightTrustScorer trustScorer
    ) {
        this.registry = registry;
        this.contributionValidator = contributionValidator;
        this.aggregationEngine = aggregationEngine;
        this.adequacyGuard = adequacyGuard;
        this.verificationEngine = verificationEngine;
        this.consistencyChecker = consistencyChecker;
        this.trustScorer = trustScorer;
    }

    /**
     * Filter findings before synthesis — reject semantically/mathematically invalid discoveries.
     */
    public GovernedFindings governBeforeSynthesis(
            StructuredFindingsBundle bundle,
            ExecutionFindings executionFindings,
            InvestigationPlan plan
    ) {
        AnalyticalExecutionMode mode = resolveExecutionMode(plan);
        return governBeforeSynthesis(bundle, executionFindings, mode);
    }

    public GovernedFindings governBeforeSynthesis(
            StructuredFindingsBundle bundle,
            ExecutionFindings executionFindings,
            AnalyticalExecutionMode executionMode
    ) {
        if (bundle == null || !bundle.hasStructuredFindings()) {
            return GovernedFindings.empty();
        }

        MaterializedQueryResult materialized = executionFindings != null
                ? executionFindings.materializedResult() : null;

        List<AnalyticalFinding> accepted = new ArrayList<>();
        List<GovernanceAudit> audits = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int total = bundle.allFindings().size();

        for (AnalyticalFinding f : bundle.allFindings()) {
            GovernanceDecision decision = evaluateFinding(f, materialized, false, executionMode);
            if (decision.accept()) {
                accepted.add(decision.correctedFinding() != null ? decision.correctedFinding() : f);
                audits.add(decision.audit());
            } else {
                rejected.addAll(decision.reasons());
                audits.add(decision.audit());
                log.warn("[governance] rejected before synthesis: {} — {}",
                        f.findingType(), decision.reasons());
            }
        }

        StructuredFindingsBundle governed = StructuredFindingsBundle.fromGroundedFindings(
                accepted, bundle.primaryFindingType());

        return new GovernedFindings(governed, audits, rejected, total - accepted.size(), total);
    }

    /**
     * Verify grounded findings before UI — recompute metrics, cross-check chart/narrative.
     */
    public GovernedPresentation governBeforePresentation(
            ReasoningResult reasoning,
            ExecutionFindings executionFindings,
            List<GovernanceAudit> priorAudits,
            EvidenceCoverageReport coverage,
            ConstitutionReview constitution,
            int priorRejected,
            int priorTotal
    ) {
        return governBeforePresentation(
                reasoning, executionFindings, priorAudits, coverage, constitution,
                priorRejected, priorTotal, AnalyticalExecutionMode.STRICT_SEMANTIC);
    }

    public GovernedPresentation governBeforePresentation(
            ReasoningResult reasoning,
            ExecutionFindings executionFindings,
            List<GovernanceAudit> priorAudits,
            EvidenceCoverageReport coverage,
            ConstitutionReview constitution,
            int priorRejected,
            int priorTotal,
            AnalyticalExecutionMode executionMode
    ) {
        if (reasoning == null || reasoning.prioritizedFindings().isEmpty()) {
            double trust = trustScorer.score(List.of(), priorAudits, coverage, constitution,
                    priorRejected, priorTotal).confidence();
            return GovernedPresentation.empty(trust);
        }

        MaterializedQueryResult materialized = executionFindings != null
                ? executionFindings.materializedResult() : null;
        boolean soft = executionMode != null && executionMode.allowsSoftGovernance();

        List<GroundedAnalyticalFinding> accepted = new ArrayList<>();
        List<GovernanceAudit> audits = new ArrayList<>(priorAudits != null ? priorAudits : List.of());
        List<String> rejected = new ArrayList<>();

        for (GroundedAnalyticalFinding g : reasoning.prioritizedFindings()) {
            GovernanceDecision decision = evaluateFinding(g.finding(), materialized, true, executionMode);
            if (!decision.accept() && !soft) {
                rejected.addAll(decision.reasons());
                audits.add(decision.audit());
                continue;
            }

            var consistency = consistencyChecker.check(g);
            if (!consistency.consistent() && !soft) {
                rejected.addAll(consistency.issues());
                audits.add(GovernanceAudit.rejected(
                        g.finding().findingType().name(), consistency.issues()));
                log.warn("[governance] consistency failed: {}", consistency.issues());
                continue;
            }

            var vr = verificationEngine.verify(g.finding(), materialized);
            if (!vr.verified() && !soft) {
                rejected.addAll(vr.issues());
                audits.add(GovernanceAudit.rejected(
                        g.finding().findingType().name(), vr.issues()));
                log.warn("[governance] verification failed: {}", vr.issues());
                continue;
            }

            if (soft && (!decision.accept() || !consistency.consistent() || !vr.verified())) {
                log.info("[governance] soft-accepting finding in {} mode: {}",
                        executionMode, g.finding().findingType());
            }

            EvidenceBackedNarrative evidence = buildEvidenceNarrative(
                    g, vr.denominator(), decision.audit().statisticalStrength());

            String safeNarrative = evidence.claims().isEmpty()
                    ? g.businessNarrative()
                    : evidence.claims().getFirst().sentence();

            accepted.add(new GroundedAnalyticalFinding(
                    g.finding(),
                    g.statistics(),
                    safeNarrative,
                    g.comparativeNarrative(),
                    g.chartSpec(),
                    g.priorityScore(),
                    evidence,
                    decision.audit().statisticalStrength()
            ));
            audits.add(GovernanceAudit.passed(
                    g.finding().findingType().name(), decision.audit().statisticalStrength()));
        }

        ChartSpec primaryChart = accepted.isEmpty() ? null : accepted.getFirst().chartSpec();
        ReasoningResult governed = new ReasoningResult(accepted, primaryChart);

        TrustScore trust = trustScorer.score(
                accepted, audits, coverage, constitution,
                priorRejected + rejected.size(), priorTotal);

        return new GovernedPresentation(governed, trust, rejected, audits);
    }

    private GovernanceDecision evaluateFinding(
            AnalyticalFinding f,
            MaterializedQueryResult materialized,
            boolean strict,
            AnalyticalExecutionMode executionMode
    ) {
        List<String> reasons = new ArrayList<>();
        boolean soft = executionMode != null && executionMode.allowsSoftGovernance();

        var agg = aggregationEngine.validate(f);
        if (!agg.valid()) reasons.addAll(agg.violations());

        var adequacy = adequacyGuard.assess(f, materialized);
        if (!adequacy.adequate()) {
            if (soft && reasons.isEmpty()) {
                log.debug("[governance] adequacy soft-warning: {}", adequacy.issues());
            } else if (!soft || strict) {
                reasons.addAll(adequacy.issues());
            }
        }

        if (f instanceof ContributionFinding c) {
            var vr = verificationEngine.verify(c, materialized);
            if (!vr.verified() && (!soft || strict)) {
                reasons.addAll(vr.issues());
            }
        }

        boolean accept = reasons.isEmpty() || (soft && agg.valid());

        double statStrength = adequacy.statisticalStrength();
        GovernanceAudit audit = accept
                ? GovernanceAudit.passed(f.findingType().name(), statStrength)
                : GovernanceAudit.rejected(f.findingType().name(), reasons);

        AnalyticalFinding corrected = f instanceof ContributionFinding c
                ? correctContributionNarrative(c) : f;

        return new GovernanceDecision(accept, corrected, audit, reasons);
    }

    private AnalyticalExecutionMode resolveExecutionMode(InvestigationPlan plan) {
        if (plan != null && plan.questionResolution() != null
                && plan.questionResolution().executionMode() != null) {
            return plan.questionResolution().executionMode();
        }
        return AnalyticalExecutionMode.STRICT_SEMANTIC;
    }

    private ContributionFinding correctContributionNarrative(ContributionFinding c) {
        var def = registry.resolve(c.metricLabel());
        String metric = def.map(MetricSemanticDefinition::displayLabel).orElse(c.metricLabel());
        double share = c.topContributorSharePct();

        String summary;
        if (share >= 95) {
            summary = String.format(Locale.ROOT,
                    "%s is heavily concentrated in %s (%.1f%% of observed buckets).",
                    metric, c.topContributor(), share);
        } else {
            summary = c.executiveSummary();
        }

        if (summary != null && summary.toLowerCase(Locale.ROOT).contains("100% of revenue")
                && share < 99) {
            summary = String.format(Locale.ROOT,
                    "%s is concentrated in %s (%.1f%% share among analyzed distance bands).",
                    metric, c.topContributor(), share);
        }

        return new ContributionFinding(
                c.dimensionLabel(), c.segments(), c.topContributor(),
                c.topContributorSharePct(), c.concentrationRatio(),
                c.giniCoefficient(), c.leaderToTailRatio(), summary, c.metricLabel());
    }

    private EvidenceBackedNarrative buildEvidenceNarrative(
            GroundedAnalyticalFinding g,
            DenominatorContext denominator,
            double statisticalStrength
    ) {
        AnalyticalFinding f = g.finding();
        var def = registry.resolve(metricLabel(f));
        AggregationType agg = def.map(MetricSemanticDefinition::aggregationType)
                .orElse(AggregationType.SUM);
        String key = def.map(MetricSemanticDefinition::metricKey).orElse("value");
        String label = def.map(MetricSemanticDefinition::displayLabel).orElse(key);

        double magnitude = f.magnitude();
        double value = switch (f) {
            case ContributionFinding c -> c.segments().isEmpty() ? 0 : c.segments().getFirst().value();
            case RankingFinding r -> r.leaderValue();
            case ComparativeFinding c -> c.valueA();
            case CorrelationFinding c -> c.correlationCoefficient();
            case EfficiencyFinding e -> e.bestEfficiency();
            case TemporalPatternFinding t -> t.peakValue();
        };

        return EvidenceBackedNarrative.create(
                f.findingType().name(), key, label, agg, denominator,
                g.businessNarrative(), value, magnitude, statisticalStrength);
    }

    private String metricLabel(AnalyticalFinding f) {
        return switch (f) {
            case ContributionFinding c -> c.metricLabel();
            case RankingFinding r -> r.metricLabel();
            case ComparativeFinding c -> c.metricLabel();
            case CorrelationFinding c -> c.targetVariable();
            case EfficiencyFinding e -> e.numeratorLabel();
            case TemporalPatternFinding t -> t.temporalDimension();
        };
    }

    private record GovernanceDecision(
            boolean accept,
            AnalyticalFinding correctedFinding,
            GovernanceAudit audit,
            List<String> reasons
    ) {}

    public record GovernedFindings(
            StructuredFindingsBundle bundle,
            List<GovernanceAudit> audits,
            List<String> rejectReasons,
            int rejectedCount,
            int totalCount
    ) {
        static GovernedFindings empty() {
            return new GovernedFindings(
                    StructuredFindingsBundle.empty(), List.of(), List.of(), 0, 0);
        }
    }

    public record GovernedPresentation(
            ReasoningResult reasoning,
            TrustScore trustScore,
            List<String> rejectReasons,
            List<GovernanceAudit> audits
    ) {
        static GovernedPresentation empty(double trust) {
            return new GovernedPresentation(
                    ReasoningResult.empty(),
                    new TrustScore(trust, new InsightTrustScorer.TrustBreakdown(0, 0, 0, 0, 0, 0)),
                    List.of(), List.of());
        }
    }
}
