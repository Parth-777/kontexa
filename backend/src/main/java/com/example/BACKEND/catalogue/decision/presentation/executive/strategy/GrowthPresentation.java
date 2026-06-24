package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.decision.presentation.executive.PresentationValueSanitizer;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GrowthPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.GROWTH;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        String measureCol = ctx.measureColumn(model);
        String partitionCol = ctx.partitionColumn(model, rows);
        String measureLabel = ctx.labelFor(measureCol);
        String partitionLabel = ctx.labelFor(partitionCol);

        List<Map<String, Object>> displayRows = ctx.capRows(rows, model);
        ExecutivePresentation.PresentationTable table = PresentationTableSupport.trendTable(
                ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                measureLabel + " growth over time");

        List<ExecutivePresentation.KpiCard> kpis = new ArrayList<>();
        List<String> insights = new ArrayList<>();
        insights.add("Growth analysis across " + displayRows.size() + " period(s)");

        if (displayRows.size() >= 2) {
            double latest = ctx.toDouble(displayRows.getLast().get(
                    PresentationBuildContext.findColumnKey(displayRows.getLast(), measureCol)));
            double prior = ctx.toDouble(displayRows.get(displayRows.size() - 2).get(
                    PresentationBuildContext.findColumnKey(displayRows.get(displayRows.size() - 2), measureCol)));
            double growth = prior != 0 ? ((latest - prior) / prior) * 100.0 : Double.NaN;
            kpis.add(ctx.kpiCard("Latest " + measureLabel, latest, measureCol));
            kpis.add(ctx.kpiCard("Prior period", prior, measureCol));
            kpis.add(new ExecutivePresentation.KpiCard(
                    "Period growth",
                    ctx.numericString(growth),
                    ctx.formatGrowth(growth),
                    ""));
            if (PresentationValueSanitizer.isUnavailable(growth)) {
                insights.add(PresentationValueSanitizer.growthUnavailableMessage());
            } else {
                insights.add("Period-over-period growth: " + ctx.formatGrowth(growth));
            }
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                kpis,
                table,
                List.of(new ExecutivePresentation.ChartHint(
                        "LINE", table.title(), null, null, partitionCol, measureCol, "currency")),
                ctx.summary(type(), rows.size(), displayRows.size(),
                        measureLabel, partitionLabel, table.title(), "LINE", insights),
                insights,
                List.of(),
                List.of(
                        "What drove the largest period-over-period change?",
                        "How does this growth compare across segments?"));
    }
}
