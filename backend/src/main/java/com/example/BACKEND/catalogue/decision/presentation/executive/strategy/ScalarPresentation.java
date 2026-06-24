package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ScalarPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.SCALAR;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        String measureCol = ctx.measureColumn(model);
        String measureLabel = ctx.labelFor(measureCol);
        Map<String, Object> row = rows.getFirst();
        String rawKey = PresentationBuildContext.findColumnKey(row, measureCol);
        double value = ctx.toDouble(row.get(rawKey));
        String formatted = ctx.formatMetric(value, measureCol);

        ExecutivePresentation.KpiCard kpi = ctx.kpiCard(measureLabel, value, measureCol);

        List<String> highlights = List.of(
                measureLabel + ": " + formatted,
                "Single scalar result from warehouse");

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(kpi),
                ctx.emptyTable(),
                List.of(),
                ctx.summary(type(), rows.size(), 1, measureLabel, "",
                        "", "NONE", highlights),
                highlights,
                List.of(),
                List.of("How does this compare to prior periods?", "What segments drive this metric?"));
    }
}
