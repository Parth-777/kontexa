package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OutlierPresentation implements PresentationStrategy {

    private static final double OUTLIER_SIGMA = 3.0;

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.OUTLIER;
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

        ExecutivePresentation.PresentationTable table = PresentationTableSupport.outlierTable(
                ctx, displayRows, measureCol, partitionCol, measureLabel, partitionLabel,
                "Outlier detection: " + measureLabel, mean, stdDev, OUTLIER_SIGMA);

        long outlierCount = displayRows.stream()
                .mapToDouble(row -> ctx.toDouble(row.get(
                        PresentationBuildContext.findColumnKey(row, measureCol))))
                .filter(v -> !Double.isNaN(v))
                .filter(v -> Math.abs(ctx.zScore(v, mean, stdDev)) >= OUTLIER_SIGMA)
                .count();

        List<String> insights = new ArrayList<>();
        insights.add(String.format("Mean %s: %s", measureLabel, ctx.formatMetric(mean, measureCol)));
        insights.add(String.format("Std dev: %s (%.1fσ threshold)", ctx.formatMetric(stdDev, measureCol), OUTLIER_SIGMA));
        insights.add(outlierCount + " outlier segment(s) flagged from warehouse data");

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(),
                table,
                List.of(),
                ctx.summary(type(), rows.size(), displayRows.size(),
                        measureLabel, partitionLabel, table.title(), "NONE", insights),
                insights,
                List.of("Investigate flagged outlier segments for operational or data-quality causes"),
                List.of("What explains the highest positive outlier?",
                        "Are negative outliers concentrated in a specific segment?"));
    }
}
