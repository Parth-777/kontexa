package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.clarification.AnalyticalAssumption;
import com.example.BACKEND.catalogue.decision.clarification.ClarificationOption;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;

import java.util.List;

/**
 * Structured analytical payload — executive-grade, not narrative blobs.
 */
public record AnalyticalResponse(
        String executiveSummary,
        List<FindingItem> findings,
        List<MetricItem> metrics,
        ChartSpec chartSpec,
        InsightBlock insight,
        double confidence,
        EvidencePanel evidencePanel,
        AnalyticalAssumption assumption,
        List<ClarificationOption> clarificationOptions,
        boolean recoveryMode,
        List<String> availableMetrics,
        String suggestedReformulation,
        String recoveryReason,
        ExecutiveInsightCard executiveCard,
        boolean exploratoryMode,
        String explorationNote,
        PlannerConfidenceTier confidenceTier,
        AnalyticalExecutionMode executionMode,
        ExecutionTrace executionTrace,
        ResponseMode responseMode,
        TableSpec tableSpec,
        String analysisType,
        CorrelationAnalysisPayload correlationAnalysis
) {
    public AnalyticalResponse(
            String executiveSummary,
            List<FindingItem> findings,
            List<MetricItem> metrics,
            ChartSpec chartSpec,
            InsightBlock insight,
            double confidence
    ) {
        this(executiveSummary, findings, metrics, chartSpec, insight, confidence,
                EvidencePanel.empty(), null, List.of(), false, List.of(), "", "", null, false, "",
                PlannerConfidenceTier.MEDIUM, AnalyticalExecutionMode.HYBRID,
                null, ResponseMode.MIXED, TableSpec.empty(), null, null);
    }

    public AnalyticalResponse(
            String executiveSummary,
            List<FindingItem> findings,
            List<MetricItem> metrics,
            ChartSpec chartSpec,
            InsightBlock insight,
            double confidence,
            EvidencePanel evidencePanel
    ) {
        this(executiveSummary, findings, metrics, chartSpec, insight, confidence,
                evidencePanel, null, List.of(), false, List.of(), "", "", null, false, "",
                PlannerConfidenceTier.MEDIUM, AnalyticalExecutionMode.HYBRID,
                null, ResponseMode.MIXED, TableSpec.empty(), null, null);
    }
    public record FindingItem(
            String type,
            String label,
            String summary,
            double magnitude
    ) {}

    public record MetricItem(
            String key,
            String label,
            String value,
            String unit,
            String delta,
            String deltaPct
    ) {}

    public record InsightBlock(String title, String text) {}
}
