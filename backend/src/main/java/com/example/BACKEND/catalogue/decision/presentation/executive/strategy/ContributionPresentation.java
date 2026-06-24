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
public class ContributionPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.CONTRIBUTION;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        String measureCol = ctx.measureColumn(model);
        String secondaryCol = ctx.secondaryColumn(model);
        String partitionCol = ctx.partitionColumn(model, rows);
        String measureLabel = ctx.labelFor(measureCol);
        String partitionLabel = ctx.labelFor(partitionCol);

        if (partitionCol != null && rows.size() > 1) {
            List<Map<String, Object>> displayRows = ctx.capRows(rows, model);
            ExecutivePresentation.PresentationTable table = PresentationTableSupport.groupedTable(
                    ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                    measureLabel + " contribution by " + partitionLabel, true);
            ExecutivePresentation.ChartHint hint = ctx.chartHint(
                    "DONUT", table.title(), partitionCol, measureCol, "currency");
            List<String> insights = List.of("Contribution table with share percentages");
            return ExecutivePresentationFactory.create(
                    type().name(),
                    List.of(),
                    table,
                    List.of(hint),
                    ctx.summary(type(), rows.size(), displayRows.size(),
                            measureLabel, partitionLabel, table.title(), "PIE", insights),
                    insights,
                    List.of(),
                    List.of("Which segment contributes the largest share?"));
        }

        double numerator = ctx.aggregateColumn(rows, measureCol);
        double denominator = secondaryCol != null
                ? ctx.aggregateColumn(rows, secondaryCol)
                : numerator;
        String numLabel = measureLabel;
        String denomLabel = ctx.labelFor(secondaryCol);
        double share = denominator != 0 ? (numerator / denominator) * 100.0 : Double.NaN;
        double remaining = PresentationValueSanitizer.isUnavailable(share) ? Double.NaN : 100.0 - share;

        List<ExecutivePresentation.KpiCard> kpis = List.of(
                ctx.kpiCard(numLabel, numerator, measureCol),
                ctx.kpiCard("Total", denominator, secondaryCol != null ? secondaryCol : measureCol),
                ctx.kpiCard("Contribution", share, "percent"),
                ctx.kpiCard("Remaining", remaining, "percent"));

        List<String> insights = new ArrayList<>();
        insights.add(numLabel + ": " + ctx.formatMetric(numerator, measureCol));
        insights.add("Total: " + ctx.formatMetric(denominator, secondaryCol != null ? secondaryCol : measureCol));
        if (PresentationValueSanitizer.isUnavailable(share)) {
            insights.add(PresentationValueSanitizer.contributionUnavailableMessage());
        } else {
            insights.add("Contribution: " + ctx.formatShare(share));
        }
        if (PresentationValueSanitizer.isUnavailable(remaining)) {
            insights.add(PresentationValueSanitizer.remainingUnavailableMessage());
        } else {
            insights.add("Remaining: " + ctx.formatShare(remaining));
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                kpis,
                ctx.emptyTable(),
                List.of(ctx.chartHint("DONUT", numLabel + " contribution", numLabel, denomLabel, "currency")),
                ctx.summary(type(), rows.size(), rows.size(),
                        measureLabel, "", "Contribution summary", "PIE", insights),
                insights,
                List.of(),
                List.of("What would increase the contribution share?"));
    }
}
