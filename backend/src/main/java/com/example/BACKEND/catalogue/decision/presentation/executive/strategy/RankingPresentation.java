package com.example.BACKEND.catalogue.decision.presentation.executive.strategy;

import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationFactory;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RankingPresentation implements PresentationStrategy {

    @Override
    public PresentationStrategyType type() {
        return PresentationStrategyType.RANKING;
    }

    @Override
    public ExecutivePresentation build(
            CanonicalQueryModel model,
            List<Map<String, Object>> rows,
            PresentationBuildContext ctx
    ) {
        String measureCol = ctx.measureColumn(model);
        String partitionCol = ctx.partitionColumn(model, rows);
        String secondaryCol = ctx.secondaryColumn(model);
        String measureLabel = ctx.labelFor(measureCol);
        String partitionLabel = ctx.labelFor(partitionCol);

        String segmentCol = partitionCol != null
                ? partitionCol
                : ctx.firstNonMetricColumn(rows, measureCol, secondaryCol);
        String segmentLabel = partitionCol != null ? partitionLabel : ctx.labelFor(segmentCol);

        List<Map<String, Object>> sorted = ctx.sortRows(model, rows, measureCol);
        int displayCount = Math.min(ctx.rankingDefaultRows(), sorted.size());
        List<Map<String, Object>> displayRows = sorted.subList(0, displayCount);

        ExecutivePresentation.PresentationTable table = PresentationTableSupport.rankedTable(
                ctx, displayRows, measureCol, segmentCol, measureLabel, segmentLabel,
                "Top " + measureLabel);

        ExecutivePresentation.ChartHint hint = ctx.chartHint(
                "HBAR", segmentLabel + " by " + measureLabel, segmentCol, measureCol, "currency");

        List<String> highlights = List.of(
                "Ranked executive table with " + displayCount + " row(s)",
                "Recommended chart: HBAR");

        return ExecutivePresentationFactory.create(
                type().name(),
                List.of(),
                table,
                List.of(hint),
                ctx.summary(type(), rows.size(), displayCount, measureLabel, segmentLabel,
                        table.title(), "HBAR", highlights),
                highlights,
                List.of(),
                List.of("What separates rank 1 from rank 2?", "Which segments are below the top " + displayCount + "?"));
    }
}
