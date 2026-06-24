package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import org.springframework.stereotype.Component;

/**
 * Selects KPI / TABLE / CHART / MIXED rendering from intent and data shape.
 */
@Component
public class ResponseModePlanner {

    public ResponseMode plan(
            InvestigationPlan plan,
            ExecutionFindings findings,
            int queryRowCount,
            boolean hasChart
    ) {
        AnalyticalIntentType intent = plan != null
                ? plan.intentType() : AnalyticalIntentType.GENERAL_ANALYSIS;

        int groupCount = groupCount(findings);
        boolean singleScalar = groupCount <= 1 && queryRowCount <= 1;

        if (singleScalar || intent == AnalyticalIntentType.COMPOSITION) {
            return ResponseMode.KPI;
        }
        if (intent == AnalyticalIntentType.TREND_ANALYSIS
                || intent == AnalyticalIntentType.FORECASTING) {
            return ResponseMode.CHART;
        }
        if (intent == AnalyticalIntentType.RANKING || intent == AnalyticalIntentType.EFFICIENCY) {
            return groupCount <= 12 ? ResponseMode.TABLE : ResponseMode.MIXED;
        }
        if (intent == AnalyticalIntentType.CONTRIBUTION
                || intent == AnalyticalIntentType.SEGMENTATION) {
            if (groupCount >= 8) return ResponseMode.MIXED;
            if (groupCount <= 6) return ResponseMode.MIXED;
            return hasChart ? ResponseMode.MIXED : ResponseMode.TABLE;
        }
        if (intent == AnalyticalIntentType.COMPARISON) {
            return groupCount <= 4 ? ResponseMode.TABLE : ResponseMode.MIXED;
        }
        if (groupCount <= 8) return ResponseMode.TABLE;
        if (groupCount > 12 && hasChart) return ResponseMode.CHART;
        return ResponseMode.MIXED;
    }

    private int groupCount(ExecutionFindings findings) {
        if (findings == null || findings.materializedResult() == null
                || findings.materializedResult().primaryGrouping() == null) {
            return 0;
        }
        var entries = findings.materializedResult().primaryGrouping().rankedEntries();
        return entries != null ? entries.size() : 0;
    }
}
