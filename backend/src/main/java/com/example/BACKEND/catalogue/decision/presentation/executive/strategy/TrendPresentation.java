package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TrendPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.TREND;
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
                measureLabel + " over time");

        ExecutivePresentation.ChartHint hint = new ExecutivePresentation.ChartHint(
                "LINE", table.title(), null, null, partitionCol, measureCol, "currency");

        List<String> insights = List.of("Time-series table with period-over-period growth");

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(),
                table,
                List.of(hint),
                ctx.summary(type(), rows.size(), displayRows.size(),
                        measureLabel, partitionLabel, table.title(), "LINE", insights),
                insights,
                List.of(),
                List.of("Which period had the strongest growth?", "Is the trend accelerating or decelerating?"));
    }
}
