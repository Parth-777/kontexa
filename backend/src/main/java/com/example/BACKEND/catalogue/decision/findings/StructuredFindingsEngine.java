package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * StructuredFindingsEngine.
 *
 * Sits between DYNAMIC_ANALYTICAL_EXECUTION and EXECUTIVE_SYNTHESIS.
 *
 * Converts pre-computed materialized groupings into typed analytical findings
 * that the LLM narrates — instead of receiving flat metric observations.
 *
 * Routing logic:
 *   1. From primaryGrouping → always try ContributionFinding + RankingFinding
 *   2. From primaryGrouping + efficiencyRatio entries → EfficiencyFinding
 *   3. From temporal groupings → TemporalPatternFinding per dimension
 *   4. From top-2 of primaryGrouping → ComparativeFinding
 *   5. From all non-primary groupings → additional RankingFindings
 *
 * The primaryFindingType is determined by the investigation plan's intent.
 */
@Service
public class StructuredFindingsEngine {

    private static final Logger log = LoggerFactory.getLogger(StructuredFindingsEngine.class);

    private final ContributionFindingProducer   contributionProducer;
    private final RankingFindingProducer        rankingProducer;
    private final EfficiencyFindingProducer     efficiencyProducer;
    private final TemporalPatternFindingProducer temporalProducer;
    private final ComparativeFindingProducer    comparativeProducer;
    private final PresentationLabelResolver     labels;

    public StructuredFindingsEngine(
            ContributionFindingProducer   contributionProducer,
            RankingFindingProducer        rankingProducer,
            EfficiencyFindingProducer     efficiencyProducer,
            TemporalPatternFindingProducer temporalProducer,
            ComparativeFindingProducer    comparativeProducer,
            PresentationLabelResolver     labels
    ) {
        this.contributionProducer = contributionProducer;
        this.rankingProducer      = rankingProducer;
        this.efficiencyProducer   = efficiencyProducer;
        this.temporalProducer     = temporalProducer;
        this.comparativeProducer  = comparativeProducer;
        this.labels               = labels;
    }

    public StructuredFindingsBundle produce(ExecutionFindings executionFindings,
                                            InvestigationPlan plan) {
        if (executionFindings == null || !executionFindings.hasContent()) {
            log.info("[findings] no execution findings — returning empty bundle");
            return StructuredFindingsBundle.empty();
        }

        MaterializedQueryResult matResult = executionFindings.materializedResult();
        if (matResult == null || !matResult.hasContent()) {
            log.info("[findings] no materialized result — returning empty bundle");
            return StructuredFindingsBundle.empty();
        }

        if (matResult.resultType() == com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType.CORRELATION_RESULT
                && matResult.correlation() != null) {
            var c = matResult.correlation();
            CorrelationFinding correlation = new CorrelationFinding(
                    c.sourceVariable(),
                    c.targetVariable(),
                    c.correlationCoefficient(),
                    c.sampleSize(),
                    c.strength(),
                    c.direction(),
                    c.interpretation(),
                    c.interpretation());
            return new StructuredFindingsBundle(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(correlation),
                    FindingType.CORRELATION, 1, true);
        }

        if (matResult.resultType() == com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType.SCALAR_RESULT
                && matResult.scalar() != null
                && !matResult.findings().isEmpty()) {
            var s = matResult.scalar();
            ComparativeFinding scalarFinding = new ComparativeFinding(
                    s.metricLabel(), "baseline", s.value(), 0,
                    s.value(), 0, "SCALAR", 1.0, s.metricLabel(),
                    matResult.findings().getFirst().findingText());
            return new StructuredFindingsBundle(
                    List.of(), List.of(), List.of(), List.of(), List.of(scalarFinding), List.of(),
                    FindingType.COMPARATIVE, 1, true);
        }

        String metricLabel = labels.resolveMetric(
                matResult.valueMetricLabel() != null ? matResult.valueMetricLabel() : "Revenue");
        AnalyticalIntentType intent = plan != null ? plan.intentType() : AnalyticalIntentType.RANKING;

        List<ContributionFinding>    contributions = new ArrayList<>();
        List<RankingFinding>         rankings      = new ArrayList<>();
        List<EfficiencyFinding>      efficiencies  = new ArrayList<>();
        List<TemporalPatternFinding> temporals     = new ArrayList<>();
        List<ComparativeFinding>     comparatives  = new ArrayList<>();

        // ── Primary grouping ────────────────────────────────────────────
        MaterializedGrouping primary = matResult.primaryGrouping();
        if (primary != null && primary.hasData()) {
            log.info("[findings] processing primary grouping: {} ({} entries)",
                    primary.spec().displayLabel(), primary.rankedEntries().size());

            // Contribution breakdown
            contributionProducer.produce(primary, metricLabel).ifPresent(contributions::add);

            // Ranking
            rankingProducer.produce(primary, metricLabel).ifPresent(rankings::add);

            // Efficiency (only if entries have meaningful ratios)
            boolean hasEfficiency = primary.rankedEntries().stream()
                    .anyMatch(e -> e.efficiencyRatio() > 0 && e.volumeCount() > 0);
            if (hasEfficiency) {
                efficiencyProducer.produce(primary, metricLabel, "unit")
                        .ifPresent(efficiencies::add);
            }

            // Comparative: top-2 and leader-vs-tail
            comparatives.addAll(comparativeProducer.produce(primary, metricLabel));
        }

        // ── All other groupings ─────────────────────────────────────────
        for (MaterializedGrouping grouping : matResult.groupings()) {
            if (grouping == primary) continue; // already processed
            if (!grouping.hasData())  continue;

            String label = grouping.spec().displayLabel();

            if (grouping.spec().isTemporal()) {
                // Temporal grouping → TemporalPatternFinding
                temporalProducer.produce(grouping).ifPresent(t -> {
                    temporals.add(t);
                    log.info("[findings] temporal finding: {}", label);
                });
            } else {
                // Additional entity grouping → more RankingFinding
                rankingProducer.produce(grouping, metricLabel).ifPresent(r -> {
                    rankings.add(r);
                    log.info("[findings] additional ranking finding: {}", label);
                });
            }
        }

        // ── If primary grouping is temporal, promote temporal findings ──
        if (primary != null && primary.spec().isTemporal()) {
            temporalProducer.produce(primary).ifPresent(temporals::add);
        }

        FindingType primaryType = determinePrimaryType(intent, temporals, contributions, rankings);
        int totalCount = contributions.size() + rankings.size()
                + efficiencies.size() + temporals.size() + comparatives.size();

        log.info("[findings] produced: contributions={} rankings={} efficiencies={} temporals={} comparatives={} primary={}",
                contributions.size(), rankings.size(), efficiencies.size(),
                temporals.size(), comparatives.size(), primaryType);

        return new StructuredFindingsBundle(
                contributions, rankings, efficiencies, temporals, comparatives, List.of(),
                primaryType, totalCount, totalCount > 0);
    }

    // ─── Primary type selection ───────────────────────────────────────────

    private FindingType determinePrimaryType(
            AnalyticalIntentType intent,
            List<TemporalPatternFinding> temporals,
            List<ContributionFinding> contributions,
            List<RankingFinding> rankings
    ) {
        AnalyticalIntentType canonical = intent != null ? intent.canonical() : AnalyticalIntentType.RANKING;
        return switch (canonical) {
            case TREND_ANALYSIS, FORECASTING ->
                    !temporals.isEmpty() ? FindingType.TEMPORAL_PATTERN : FindingType.RANKING;
            case CONTRIBUTION, COMPOSITION ->
                    !contributions.isEmpty() ? FindingType.CONTRIBUTION_BREAKDOWN : FindingType.RANKING;
            case DISTRIBUTION, SEGMENTATION ->
                    !contributions.isEmpty() ? FindingType.CONTRIBUTION_BREAKDOWN : FindingType.RANKING;
            case EFFICIENCY ->
                    FindingType.EFFICIENCY;
            case RANKING, STRATEGIC_PRIORITIZATION ->
                    !rankings.isEmpty() ? FindingType.RANKING : FindingType.CONTRIBUTION_BREAKDOWN;
            case COMPARISON, ROOT_CAUSE_INVESTIGATION, CORRELATION, RELATIONSHIP ->
                    FindingType.COMPARATIVE;
            case ANOMALY_DETECTION ->
                    !rankings.isEmpty() ? FindingType.RANKING : FindingType.COMPARATIVE;
            default ->
                    !rankings.isEmpty() ? FindingType.RANKING
                    : !contributions.isEmpty() ? FindingType.CONTRIBUTION_BREAKDOWN
                    : FindingType.RANKING;
        };
    }
}
