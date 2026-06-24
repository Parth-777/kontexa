package com.example.BACKEND.catalogue.decision.presentation.executive;

import java.util.List;
import java.util.Map;

/**
 * Factory for {@link ExecutivePresentation} with optional enrichment fields.
 */
public final class ExecutivePresentationFactory {

    private ExecutivePresentationFactory() {}

    public static ExecutivePresentation create(
            String type,
            List<ExecutivePresentation.KpiCard> kpis,
            ExecutivePresentation.PresentationTable table,
            List<ExecutivePresentation.ChartHint> charts,
            ExecutivePresentation.PresentationSummary summary
    ) {
        return create(type, kpis, table, charts, summary, List.of(), List.of(), List.of(), Map.of());
    }

    public static ExecutivePresentation create(
            String type,
            List<ExecutivePresentation.KpiCard> kpis,
            ExecutivePresentation.PresentationTable table,
            List<ExecutivePresentation.ChartHint> charts,
            ExecutivePresentation.PresentationSummary summary,
            List<String> insights,
            List<String> recommendations,
            List<String> followUps
    ) {
        return create(type, kpis, table, charts, summary, insights, recommendations, followUps, Map.of());
    }

    public static ExecutivePresentation create(
            String type,
            List<ExecutivePresentation.KpiCard> kpis,
            ExecutivePresentation.PresentationTable table,
            List<ExecutivePresentation.ChartHint> charts,
            ExecutivePresentation.PresentationSummary summary,
            List<String> insights,
            List<String> recommendations,
            List<String> followUps,
            Map<String, Object> statistics
    ) {
        return new ExecutivePresentation(
                type,
                kpis != null ? kpis : List.of(),
                table != null ? table : new ExecutivePresentation.PresentationTable("", List.of(), List.of()),
                charts != null ? charts : List.of(),
                summary,
                insights != null ? insights : List.of(),
                recommendations != null ? recommendations : List.of(),
                followUps != null ? followUps : List.of(),
                statistics != null ? statistics : Map.of());
    }

    public static ExecutivePresentation withStatistics(
            ExecutivePresentation presentation,
            Map<String, Object> statistics
    ) {
        return new ExecutivePresentation(
                presentation.type(),
                presentation.kpis(),
                presentation.table(),
                presentation.charts(),
                presentation.summary(),
                presentation.insights(),
                presentation.recommendations(),
                presentation.followUps(),
                statistics != null ? statistics : Map.of());
    }
}
