package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.governance.MetricDecompositionBinding;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Verifies that investigation metric binding fields are verbatim projections of {@link AnalysisPlan}.
 */
public final class AnalysisPlanProjectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanProjectionVerifier.class);

    private AnalysisPlanProjectionVerifier() {}

    public static void verify(
            AnalysisPlan contract,
            AnalyticalIntentType intentType,
            MetricDecompositionBinding binding,
            AggregationType aggregation
    ) {
        if (contract == null) {
            throw new AnalysisPlanProjectionException("AnalysisPlan is required for projection");
        }
        if (binding == null) {
            throw new AnalysisPlanProjectionException("MetricDecompositionBinding is required for projection");
        }

        AnalyticalIntentType expectedIntent = contract.intent().toAnalyticalIntentType();
        String expectedMetric = contract.primaryMetric();
        String expectedGrouping = AnalysisPlanProjection.groupingColumn(contract);
        AggregationType expectedAggregation = aggregation;

        log.info("[projection] intent: AnalysisPlan.intent={} -> InvestigationPlan.intentType={}",
                contract.intent(), intentType);
        assertField("intent", "AnalysisPlan.intent", contract.intent().name(),
                "InvestigationPlan.intentType", intentType != null ? intentType.name() : null,
                Objects.equals(expectedIntent, intentType));

        log.info("[projection] metric: AnalysisPlan.primaryMetric={} -> metricBinding.metricColumn={}",
                expectedMetric, binding.metricColumn());
        assertField("primaryMetric", "AnalysisPlan.primaryMetric", expectedMetric,
                "metricBinding.metricColumn", binding.metricColumn(),
                Objects.equals(expectedMetric, binding.metricColumn()));

        log.info("[projection] grouping: AnalysisPlan.dimension/groupingAlias={}/{} -> metricBinding.groupingColumn={}",
                contract.dimension(), contract.groupingAlias(), binding.groupingColumn());
        assertField("groupingColumn", "AnalysisPlan.dimension|groupingAlias",
                expectedGrouping,
                "metricBinding.groupingColumn", binding.groupingColumn(),
                Objects.equals(expectedGrouping, binding.groupingColumn()));

        log.info("[projection] aggregation: AnalysisPlan.intent.sqlKind={} -> metricBinding.aggregation={}",
                contract.intent().sqlKind(), binding.aggregation());
        assertField("aggregation", "AnalysisPlan.intent (sqlKind)",
                expectedAggregation != null ? expectedAggregation.name() : null,
                "metricBinding.aggregation",
                binding.aggregation() != null ? binding.aggregation().name() : null,
                Objects.equals(expectedAggregation, binding.aggregation()));
    }

    private static void assertField(
            String field,
            String sourceLabel,
            String sourceValue,
            String targetLabel,
            String targetValue,
            boolean match
    ) {
        if (!match) {
            throw new AnalysisPlanProjectionException(String.format(
                    "Projection mismatch on %s: %s=%s != %s=%s",
                    field, sourceLabel, sourceValue, targetLabel, targetValue));
        }
    }
}
