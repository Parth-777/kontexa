package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ParetoPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.PARETO;
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

        List<Map<String, Object>> sorted = ctx.sortRows(model, rows, measureCol);
        int displayCount = Math.min(ctx.rankingDefaultRows(), sorted.size());
        List<Map<String, Object>> displayRows = sorted.subList(0, displayCount);

        ExecutivePresentation.PresentationTable table = PresentationTableSupport.paretoTable(
                ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                "Pareto: " + measureLabel + " by " + partitionLabel);

        List<String> insights = new ArrayList<>();
        insights.add("Pareto analysis of top " + displayCount + " segments by " + measureLabel);
        String cumulativeAt80 = findCumulativeThreshold(displayRows, measureCol, ctx, 80.0);
        if (cumulativeAt80 != null) {
            insights.add("80% threshold reached at rank " + cumulativeAt80);
        }

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(),
                table,
                List.of(ctx.chartHint("BAR", table.title(), partitionCol, measureCol, "currency")),
                ctx.summary(type(), rows.size(), displayCount, measureLabel, partitionLabel,
                        table.title(), "BAR", insights),
                insights,
                List.of("Focus on top contributors driving the majority of " + measureLabel),
                List.of("Which segments fall below the 80% cumulative threshold?",
                        "What actions would shift share among top contributors?"));
    }

    private static String findCumulativeThreshold(
            List<Map<String, Object>> rows,
            String measureCol,
            PresentationBuildContext ctx,
            double threshold
    ) {
        double total = ctx.sumColumn(rows, measureCol);
        if (total == 0) {
            return null;
        }
        double cumulative = 0;
        int rank = 1;
        for (Map<String, Object> row : rows) {
            double value = ctx.toDouble(row.get(PresentationBuildContext.findColumnKey(row, measureCol)));
            cumulative += (value / total) * 100.0;
            if (cumulative >= threshold) {
                return String.valueOf(rank);
            }
            rank++;
        }
        return null;
    }
}
