package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedCorrelation;
import com.example.BACKEND.catalogue.decision.presentation.CorrelationAnalysisPayload;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executive presentation for CORR / relationship analysis — never uses comparison templates.
 */
@Component
public class CorrelationExecutivePresenter {

    public CorrelationAnalysisPayload toPayload(MaterializedCorrelation c) {
        if (c == null) return null;
        String title = c.sourceVariable() + " vs " + c.targetVariable();
        return new CorrelationAnalysisPayload(
                title,
                buildSummary(c),
                c.correlationCoefficient(),
                c.sampleSize(),
                c.strength(),
                c.direction(),
                buildBusinessInterpretation(c),
                c.sourceVariable(),
                c.targetVariable());
    }

    public ExecutiveInsightCard present(
            MaterializedCorrelation correlation,
            ExecutiveConfidenceLabel confidenceLabel
    ) {
        CorrelationAnalysisPayload payload = toPayload(correlation);
        if (payload == null) {
            return ExecutiveInsightCard.empty("Correlation analysis unavailable.", confidenceLabel);
        }

        List<ExecutiveSupportingMetric> metrics = List.of(
                new ExecutiveSupportingMetric(
                        "Correlation coefficient",
                        formatCoefficient(payload.correlationCoefficient()),
                        "", ""),
                new ExecutiveSupportingMetric(
                        "Strength",
                        CorrelationAnalysisPayload.formatStrength(payload.strength()),
                        "", ""),
                new ExecutiveSupportingMetric(
                        "Sample size",
                        formatCount(payload.sampleSize()),
                        "observations", ""));

        return new ExecutiveInsightCard(
                payload.title(),
                payload.summary(),
                metrics,
                correlationChart(payload),
                payload.summary(),
                confidenceLabel,
                payload.businessInterpretation());
    }

    public ChartSpec correlationChart(CorrelationAnalysisPayload payload) {
        if (payload == null) return null;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("coefficient", payload.correlationCoefficient());
        row.put("strength", payload.strength());
        row.put("direction", payload.direction());
        row.put("source", payload.sourceVariable());
        row.put("target", payload.targetVariable());
        row.put("sample_size", payload.sampleSize());
        return new ChartSpec(
                ChartSpec.ChartType.CORRELATION,
                payload.title(),
                payload.summary(),
                null, null, null, null,
                "number", "category",
                List.of(row));
    }

    private String buildSummary(MaterializedCorrelation c) {
        double abs = Math.abs(c.correlationCoefficient());
        String target = c.targetVariable().toLowerCase(Locale.ROOT);
        if (abs < 0.1) {
            return String.format(Locale.ROOT,
                    "%s has almost no measurable relationship with %s.",
                    c.sourceVariable(), target);
        }
        if (abs < 0.3) {
            return String.format(Locale.ROOT,
                    "%s has a weak %s relationship with %s.",
                    c.sourceVariable(), c.direction(), target);
        }
        return String.format(Locale.ROOT,
                "%s shows a %s %s relationship with %s.",
                c.sourceVariable(), c.strength(), c.direction(), target);
    }

    private String buildBusinessInterpretation(MaterializedCorrelation c) {
        if (c.interpretation() != null && !c.interpretation().isBlank()) {
            return c.interpretation();
        }
        double abs = Math.abs(c.correlationCoefficient());
        if (abs < 0.1) {
            return String.format(Locale.ROOT,
                    "Changes in %s do not meaningfully predict changes in %s.",
                    c.sourceVariable().toLowerCase(Locale.ROOT),
                    c.targetVariable().toLowerCase(Locale.ROOT));
        }
        String verb = c.correlationCoefficient() < 0 ? "decrease" : "increase";
        return String.format(Locale.ROOT,
                "As %s rises, %s tends to %s (r=%.3f, n=%d).",
                c.sourceVariable().toLowerCase(Locale.ROOT),
                c.targetVariable().toLowerCase(Locale.ROOT),
                verb,
                c.correlationCoefficient(),
                c.sampleSize());
    }

    private String formatCoefficient(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String formatCount(long count) {
        return String.format(Locale.ROOT, "%,d", count);
    }
}
