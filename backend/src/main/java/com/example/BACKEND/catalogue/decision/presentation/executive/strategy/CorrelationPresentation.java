package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CorrelationPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.CORRELATION;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        CanonicalQueryModel.BivariateSpec bivariate = model.bivariate();
        String colA = bivariate != null ? bivariate.columnA() : ctx.measureColumn(model);
        String colB = bivariate != null ? bivariate.columnB() : ctx.detectSecondMetric(rows, colA);
        String labelA = ctx.labelFor(colA);
        String labelB = ctx.labelFor(colB);

        List<ExecutivePresentation.KpiCard> kpis = new ArrayList<>();
        if (rows.size() == 1) {
            Map<String, Object> row = rows.getFirst();
            double coefficient = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, "correlation_coefficient")));
            if (Double.isNaN(coefficient)) {
                coefficient = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, colA)));
            }
            if (!Double.isNaN(coefficient)) {
                kpis.add(new ExecutivePresentation.KpiCard(
                        "Correlation coefficient",
                        ctx.numericString(coefficient),
                        String.format(Locale.ROOT, "%.3f", coefficient),
                        ""));
            }
        }

        ExecutivePresentation.ChartHint hint = new ExecutivePresentation.ChartHint(
                "CORRELATION",
                labelA + " vs " + labelB,
                null, null, colA, colB, "number");

        List<String> highlights = new ArrayList<>();
        highlights.add("Scatter plot: " + labelA + " vs " + labelB);
        if (!kpis.isEmpty()) {
            highlights.add("Correlation coefficient from warehouse: " + kpis.getFirst().formattedValue());
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                kpis,
                ctx.emptyTable(),
                List.of(hint),
                ctx.summary(type(), rows.size(), rows.size(),
                        labelA, labelB, "", "SCATTER", highlights),
                highlights,
                List.of(),
                List.of("Which segments show the strongest relationship?"));
    }
}
