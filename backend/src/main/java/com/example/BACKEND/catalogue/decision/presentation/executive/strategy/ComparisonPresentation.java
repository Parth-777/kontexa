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
public class ComparisonPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.COMPARISON;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        String measureCol = ctx.measureColumn(model);
        String secondaryCol = ctx.secondaryColumn(model);
        String measureLabel = ctx.labelFor(measureCol);

        String colA = measureCol;
        String colB = secondaryCol != null ? secondaryCol : ctx.detectSecondMetric(rows, colA);
        double valueA = ctx.aggregateColumn(rows, colA);
        double valueB = colB != null ? ctx.aggregateColumn(rows, colB) : Double.NaN;
        double diff = PresentationValueSanitizer.isUnavailable(valueB) ? Double.NaN : valueB - valueA;
        double pctDiff = valueA != 0 ? (diff / valueA) * 100.0 : Double.NaN;

        String labelA = ctx.labelFor(colA);
        String labelB = ctx.labelFor(colB);

        List<ExecutivePresentation.KpiCard> kpis = List.of(
                ctx.kpiCard(labelA, valueA, colA),
                ctx.kpiCard(labelB, valueB, colB),
                ctx.kpiCard("Difference", diff, colA),
                ctx.kpiCard("Percent difference", pctDiff, "percent"));

        List<String> insights = new ArrayList<>();
        insights.add(labelA + ": " + ctx.formatMetric(valueA, colA));
        insights.add(labelB + ": " + ctx.formatMetric(valueB, colB));
        insights.add("Difference: " + ctx.formatMetric(diff, colA));
        if (PresentationValueSanitizer.isUnavailable(pctDiff)) {
            insights.add(PresentationValueSanitizer.percentDifferenceUnavailableMessage());
        } else {
            insights.add("Percent difference: " + ctx.formatShare(pctDiff));
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                kpis,
                ctx.emptyTable(),
                List.of(),
                ctx.summary(type(), rows.size(), rows.size(),
                        measureLabel, "", "Metric comparison", "NONE", insights),
                insights,
                List.of(),
                List.of("What operational factors explain the gap between metrics?"));
    }
}
