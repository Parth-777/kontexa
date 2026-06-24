package com.example.BACKEND.catalogue.decision.execution;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.*;
import com.example.BACKEND.catalogue.decision.execution.StatisticalSignificanceGuard.FilteredEntities;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dynamic Analytical Execution Engine.
 *
 * Operates AFTER analytical depth computation and BEFORE evidence assembly.
 * Executes specialized computational investigations that convert raw warehouse
 * rows into entity-level, ranked, significance-filtered analytical findings.
 *
 * Pipeline:
 *   1. Construct entities dynamically (routes, zones, segments, cohorts, etc.)
 *   2. Enrich with derived metrics (revenue/minute, revenue/trip, contribution share)
 *   3. Filter for statistical significance (min sample, outlier suppression)
 *   4. Rank by primary metric and efficiency metric independently
 *   5. Generate specific, quantified structural findings
 *
 * The output {@link ExecutionFindings} is passed to the synthesis prompt so the
 * LLM receives pre-computed discoveries rather than deriving findings itself.
 *
 * Intent-aware: entity construction and metric enrichment adapt to what the
 * question is trying to answer.
 */
@Service
public class DynamicAnalyticalExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(DynamicAnalyticalExecutionEngine.class);

    private final EntityConstructionEngine    entityEngine;
    private final DynamicMetricExecutionPlanner metricPlanner;
    private final StatisticalSignificanceGuard  sigGuard;
    private final ComparativeRankingRuntime     rankingRuntime;
    private final StructuralFindingGenerator    findingGenerator;

    public DynamicAnalyticalExecutionEngine(
            EntityConstructionEngine    entityEngine,
            DynamicMetricExecutionPlanner metricPlanner,
            StatisticalSignificanceGuard  sigGuard,
            ComparativeRankingRuntime     rankingRuntime,
            StructuralFindingGenerator    findingGenerator
    ) {
        this.entityEngine     = entityEngine;
        this.metricPlanner    = metricPlanner;
        this.sigGuard         = sigGuard;
        this.rankingRuntime   = rankingRuntime;
        this.findingGenerator = findingGenerator;
    }

    public ExecutionFindings execute(
            ComputationResultSet resultSet,
            InvestigationPlan    plan
    ) {
        if (resultSet == null || resultSet.results().isEmpty())
            return ExecutionFindings.empty();

        AnalyticalIntentType intentType = plan != null
                ? plan.intentType()
                : AnalyticalIntentType.GENERAL_ANALYSIS;

        List<Map<String, Object>> allRows = mergeRows(resultSet);
        if (allRows.isEmpty()) return ExecutionFindings.empty();

        // Step 1: construct entities
        List<ConstructedEntity> rawEntities = entityEngine.construct(allRows);
        if (rawEntities.isEmpty()) {
            log.info("[execution] No entities constructed — insufficient dimensional columns");
            return ExecutionFindings.empty();
        }

        // Step 2: enrich with derived metrics
        List<ConstructedEntity> enriched = metricPlanner.enrichWithDerivedMetrics(rawEntities, intentType);

        // Step 3: statistical significance filtering
        FilteredEntities filtered = sigGuard.filter(enriched);

        // Step 4: rank by primary and efficiency metrics
        List<RankedEntity> primaryRanking   = rankingRuntime.rankByPrimary(filtered.retained());
        List<RankedEntity> efficiencyRanking = rankingRuntime.rankByEfficiency(filtered.retained());

        // Step 5: build statistical context
        double peerAvg = primaryRanking.isEmpty() ? 0 : primaryRanking.get(0).peerAverage();
        double topDecileThreshold = primaryRanking.stream()
                .filter(r -> "TOP_DECILE".equals(r.tier()))
                .mapToDouble(RankedEntity::value)
                .min().orElse(0);

        StatisticalContext stats = new StatisticalContext(
                rawEntities.size(),
                filtered.retained().size(),
                filtered.outliers().size(),
                filtered.report().minimumSampleUsed(),
                peerAvg,
                topDecileThreshold,
                filtered.report().note()
        );

        // Step 6: generate structural findings
        List<StructuralFinding> findings = findingGenerator.generate(
                filtered.retained(), primaryRanking, efficiencyRanking, stats, intentType);

        log.info("[execution] entities={} retained={} primaryRanked={} efficiencyRanked={} findings={}",
                rawEntities.size(), filtered.retained().size(),
                primaryRanking.size(), efficiencyRanking.size(), findings.size());

        return new ExecutionFindings(
                filtered.retained(), primaryRanking, efficiencyRanking, stats, findings, null);
    }

    private List<Map<String, Object>> mergeRows(ComputationResultSet rs) {
        return rs.results().stream()
                .filter(r -> r.rows() != null)
                .flatMap(r -> r.rows().stream())
                .collect(Collectors.toList());
    }
}
