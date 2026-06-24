package com.example.BACKEND.catalogue.decision.evidence;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Transforms raw {@link ComputationResultSet}s into structured {@link EvidenceObject}s.
 *
 * Pipeline per entity:
 *   1. Aggregate raw metrics, comparisons, signals from query results
 *   2. Derive comparative contexts (period-over-period, vs baseline, vs peers)
 *   3. Build investigation tree from material movements
 *   4. Score confidence
 *   5. Track lineage
 *   6. Validate before emitting
 *
 * The LLM receives ONLY this structured evidence — never raw rows.
 */
@Service
public class EvidenceAssembler {

    private static final Logger log = LoggerFactory.getLogger(EvidenceAssembler.class);

    private final ConfidenceCalculator        confidenceCalculator;
    private final EvidenceLineageTracker       lineageTracker;
    private final EvidenceValidator           validator;
    private final ComparativeReasoningEngine  comparativeEngine;
    private final InvestigationTreeBuilder    treeBuilder;

    public EvidenceAssembler(
            ConfidenceCalculator       confidenceCalculator,
            EvidenceLineageTracker      lineageTracker,
            EvidenceValidator          validator,
            ComparativeReasoningEngine comparativeEngine,
            InvestigationTreeBuilder   treeBuilder
    ) {
        this.confidenceCalculator = confidenceCalculator;
        this.lineageTracker       = lineageTracker;
        this.validator            = validator;
        this.comparativeEngine    = comparativeEngine;
        this.treeBuilder          = treeBuilder;
    }

    public List<EvidenceObject> assemble(ComputationResultSet resultSet, RegistryResolutionBundle bundle) {
        List<EvidenceObject> evidenceList = new ArrayList<>();
        Map<String, EvidenceBuilder> builders = new LinkedHashMap<>();

        for (QueryResult queryResult : resultSet.results()) {
            String entityRef = deriveEntityRef(queryResult.key(), bundle);
            builders.computeIfAbsent(entityRef, EvidenceBuilder::new).addQueryResult(queryResult);
        }

        for (EvidenceBuilder builder : builders.values()) {
            EvidenceObject evidence = build(builder, resultSet);
            if (validator.isValid(evidence)) {
                evidenceList.add(evidence);
                log.debug("[evidence] id={} entity={} comparisons={} treeNodes={} confidence={}",
                        evidence.evidenceId(), evidence.entityRef(),
                        evidence.comparativeContexts().size(),
                        evidence.investigationTree().size(),
                        evidence.confidence());
            } else {
                log.warn("[evidence] discarded invalid evidence for entity={}", builder.entityRef);
            }
        }

        return evidenceList;
    }

    // ─── private ───────────────────────────────────────────────────────────

    private EvidenceObject build(EvidenceBuilder builder, ComputationResultSet resultSet) {
        String evidenceId = "ev-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> metrics     = builder.extractMetrics();
        Map<String, Object> comparisons = builder.extractComparisons();
        Map<String, Object> signals     = builder.extractSignals();

        // Comparative intelligence layer
        List<ComparativeContext> comparativeContexts =
                comparativeEngine.derive(builder.entityRef, builder.queryResults);

        // Investigation tree from material comparative movements
        List<InvestigationNode> investigationTree =
                treeBuilder.build(comparativeContexts, builder.queryResults);

        double confidence = confidenceCalculator.calculate(
                metrics, comparisons, signals,
                builder.queryResults.size(), comparativeContexts.size()
        );

        List<String> lineageRefs = lineageTracker.buildRefs(builder.queryResults, resultSet.runId());

        return new EvidenceObject(
                evidenceId, builder.entityRef,
                metrics, comparisons, signals,
                comparativeContexts, investigationTree,
                confidence, lineageRefs
        );
    }

    private String deriveEntityRef(String queryKey, RegistryResolutionBundle bundle) {
        String[] parts = queryKey.split("__");
        if (parts.length >= 2) {
            String candidate = parts[1];
            boolean found = bundle.entities().stream()
                    .anyMatch(e -> e.key().equalsIgnoreCase(candidate));
            if (found) return candidate;
        }
        return bundle.entities().isEmpty() ? "unknown" : bundle.entities().get(0).key();
    }

    // ─── inner builder ─────────────────────────────────────────────────────

    static class EvidenceBuilder {
        final String            entityRef;
        final List<QueryResult> queryResults = new ArrayList<>();

        EvidenceBuilder(String entityRef) { this.entityRef = entityRef; }

        void addQueryResult(QueryResult qr) { queryResults.add(qr); }

        Map<String, Object> extractMetrics() {
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (QueryResult qr : queryResults) {
                if (qr.rows().isEmpty()) continue;
                qr.rows().get(0).forEach((col, val) -> {
                    if (isNumericValue(val)) metrics.put(qr.key() + "." + col, val);
                });
            }
            return metrics;
        }

        Map<String, Object> extractComparisons() {
            Map<String, Object> comparisons = new LinkedHashMap<>();
            for (QueryResult qr : queryResults) {
                if (qr.rows().size() >= 2) {
                    comparisons.put(qr.key() + ".row_count",    qr.rows().size());
                    comparisons.put(qr.key() + ".top_value",    qr.rows().get(0));
                    comparisons.put(qr.key() + ".second_value", qr.rows().get(1));
                }
            }
            return comparisons;
        }

        Map<String, Object> extractSignals() {
            Map<String, Object> signals = new LinkedHashMap<>();
            for (QueryResult qr : queryResults) {
                signals.put(qr.key() + ".elapsed_ms",  qr.elapsedMs());
                signals.put(qr.key() + ".data_points", qr.rows().size());
            }
            return signals;
        }

        private boolean isNumericValue(Object val) {
            if (val == null) return false;
            if (val instanceof Number) return true;
            try { Double.parseDouble(val.toString()); return true; }
            catch (NumberFormatException e) { return false; }
        }
    }
}
