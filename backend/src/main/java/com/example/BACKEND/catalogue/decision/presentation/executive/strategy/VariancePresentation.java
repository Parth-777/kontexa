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
public class VariancePresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.VARIANCE;
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
        double mean = ctx.meanColumn(displayRows, measureCol);
        double stdDev = ctx.stdDevColumn(displayRows, measureCol);
        double cv = mean != 0 && !Double.isNaN(stdDev) ? (stdDev / Math.abs(mean)) * 100.0 : Double.NaN;

        ExecutivePresentation.PresentationTable table = PresentationTableSupport.varianceTable(
                ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                "Variance analysis: " + measureLabel, mean, stdDev);

        List<ExecutivePresentation.KpiCard> kpis = List.of(
                ctx.kpiCard("Mean " + measureLabel, mean, measureCol),
                ctx.kpiCard("Std deviation", stdDev, measureCol),
                ctx.kpiCard("Coefficient of variation", cv, "percent"));

        List<String> insights = new ArrayList<>();
        insights.add("Variance computed across " + displayRows.size() + " warehouse segment(s)");
        if (PresentationValueSanitizer.isUnavailable(cv)) {
            insights.add(PresentationValueSanitizer.varianceUnavailableMessage());
        } else {
            insights.add("Portfolio CV: " + ctx.formatShare(cv));
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                kpis,
                table,
                List.of(ctx.chartHint("BAR", table.title(), partitionCol, measureCol, "currency")),
                ctx.summary(type(), rows.size(), displayRows.size(),
                        measureLabel, partitionLabel, table.title(), "BAR", insights),
                insights,
                List.of(),
                List.of("Which segments contribute most to overall spread?",
                        "Has variance increased or decreased over time?"));
    }
}
