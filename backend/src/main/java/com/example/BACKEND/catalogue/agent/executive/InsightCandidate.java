package com.example.BACKEND.catalogue.agent.executive;

import com.example.BACKEND.catalogue.agent.AgentDashboardResult;

import java.util.List;

/**
 * Structured insight claim before narrative — must cite evidenceRefs from CollectedData labels.
 */
public record InsightCandidate(
        String claim,
        InsightLens lens,
        String tableName,
        double impactScore,
        String badge,
        String impactLevel,
        List<String> evidenceRefs,
        List<AgentDashboardResult.MetricHighlight> metricHighlights,
        List<String> sourceColumns,
        String suggestedOwner,
        String driverSegment
) {}
