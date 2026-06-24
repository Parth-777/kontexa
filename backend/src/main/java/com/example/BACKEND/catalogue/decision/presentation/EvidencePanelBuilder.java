package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalReasoningPlan;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class EvidencePanelBuilder {

    private final MetricSemanticRegistry metricRegistry;

    public EvidencePanelBuilder(MetricSemanticRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    public EvidencePanel build(
            GroundedAnalyticalFinding primary,
            InvestigationPlan plan,
            ExecutionFindings executionFindings,
            double confidence
    ) {
        MaterializedQueryResult mat = executionFindings != null
                ? executionFindings.materializedResult() : null;
        int sampleSize = mat != null ? mat.totalRows() : 0;

        AnalyticalReasoningPlan reasoning = plan != null ? plan.reasoningPlan() : null;
        String metric = reasoning != null ? reasoning.primaryMetric() : "Value";
        String grouping = reasoning != null ? reasoning.groupingDimension() : "Segment";
        String aggregation = "SUM";

        if (reasoning != null && reasoning.metricBinding() != null) {
            metric = reasoning.metricBinding().metricLabel();
            grouping = reasoning.metricBinding().groupingLabel();
            aggregation = reasoning.metricBinding().aggregation().name();
        } else if (primary != null) {
            metric = extractMetricLabel(primary.finding());
        }

        var def = metricRegistry.resolve(metric);
        if (def.isPresent()) {
            aggregation = def.get().aggregationType().name();
            metric = def.get().displayLabel();
        }

        return new EvidencePanel(
                metric, grouping, aggregation, sampleSize, confidenceBasis(confidence, sampleSize));
    }

    private String extractMetricLabel(AnalyticalFinding finding) {
        if (finding == null) return "Value";
        return switch (finding) {
            case AnalyticalFinding.ContributionFinding c -> c.metricLabel();
            case AnalyticalFinding.RankingFinding r -> r.metricLabel();
            case AnalyticalFinding.ComparativeFinding c -> c.metricLabel();
            case AnalyticalFinding.EfficiencyFinding e -> e.numeratorLabel();
            case AnalyticalFinding.TemporalPatternFinding t -> t.temporalDimension();
            case AnalyticalFinding.CorrelationFinding c -> c.targetVariable();
        };
    }

    private String confidenceBasis(double confidence, int sampleSize) {
        if (confidence >= 0.75 && sampleSize >= 30) {
            return String.format(Locale.ROOT,
                    "Verified aggregates, n=%d, trust=%.0f%%", sampleSize, confidence * 100);
        }
        if (sampleSize > 0) {
            return String.format(Locale.ROOT,
                    "Limited sample (n=%d), trust=%.0f%%", sampleSize, confidence * 100);
        }
        return String.format(Locale.ROOT, "Low evidence density, trust=%.0f%%", confidence * 100);
    }
}
