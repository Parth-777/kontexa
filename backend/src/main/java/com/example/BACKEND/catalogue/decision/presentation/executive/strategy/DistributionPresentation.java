package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DistributionPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.DISTRIBUTION;
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
        ExecutivePresentation.PresentationTable table = PresentationTableSupport.groupedTable(
                ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                measureLabel + " by " + partitionLabel, true);

        ExecutivePresentation.ChartHint hint = ctx.chartHint("HBAR", table.title(), partitionCol, measureCol, "currency");
        List<String> insights = List.of("Distribution table with share percentages");

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(),
                table,
                List.of(hint),
                ctx.summary(type(), rows.size(), displayRows.size(),
                        measureLabel, partitionLabel, table.title(), "HBAR", insights),
                insights,
                List.of(),
                List.of("Which segment has the largest share?", "How concentrated is " + measureLabel + "?"));
    }
}
