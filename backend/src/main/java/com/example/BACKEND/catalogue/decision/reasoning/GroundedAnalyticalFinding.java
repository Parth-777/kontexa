package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.governance.EvidenceBackedNarrative;

/**
 * Single finding object coupling narrative, statistical interpretation, chart spec,
 * and evidence traceability.
 */
public record GroundedAnalyticalFinding(
        AnalyticalFinding         finding,
        StatisticalInterpretation statistics,
        String                    businessNarrative,
        String                    comparativeNarrative,
        ChartSpec                 chartSpec,
        double                    priorityScore,
        EvidenceBackedNarrative   evidence,
        double                    trustStrength
) {
    public GroundedAnalyticalFinding(
            AnalyticalFinding finding,
            StatisticalInterpretation statistics,
            String businessNarrative,
            String comparativeNarrative,
            ChartSpec chartSpec,
            double priorityScore
    ) {
        this(finding, statistics, businessNarrative, comparativeNarrative,
                chartSpec, priorityScore, null, 0);
    }
}
