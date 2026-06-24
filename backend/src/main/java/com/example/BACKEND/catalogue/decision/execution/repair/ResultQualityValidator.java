package com.example.BACKEND.catalogue.decision.execution.repair;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates analytical output quality before insight synthesis.
 */
@Component
public class ResultQualityValidator {

    public static final int MIN_GROUPS = 2;
    public static final int MIN_ROWS_THRESHOLD = 2;

    private final IntermediateResultInspector inspector;

    public ResultQualityValidator(IntermediateResultInspector inspector) {
        this.inspector = inspector;
    }

    public record QualityResult(boolean acceptable, List<String> issues) {
        public static QualityResult pass() {
            return new QualityResult(true, List.of());
        }

        public static QualityResult fail(List<String> issues) {
            return new QualityResult(false, issues);
        }
    }

    public QualityResult validateRows(List<Map<String, Object>> rows) {
        var inspection = inspector.inspect(rows);
        if (!inspection.acceptable()) {
            return QualityResult.fail(List.of(inspection.issue()));
        }
        return QualityResult.pass();
    }

    public QualityResult validateMaterialized(MaterializedQueryResult materialized) {
        List<String> issues = new ArrayList<>();
        if (materialized == null || !materialized.hasContent()) {
            issues.add("No materialized grouped result");
            return QualityResult.fail(issues);
        }
        MaterializedGrouping g = materialized.primaryGrouping();
        if (g == null || g.rankedEntries() == null || g.rankedEntries().size() < MIN_GROUPS) {
            issues.add("Fewer than " + MIN_GROUPS + " groups for meaningful comparison");
        }
        if (g != null && g.rankedEntries() != null) {
            double sum = g.rankedEntries().stream().mapToDouble(e -> e.totalValue()).sum();
            if (sum <= 0) issues.add("Aggregation values are all zero or null");
            double[] vals = g.rankedEntries().stream().mapToDouble(e -> e.totalValue()).toArray();
            if (vals.length >= 2 && variance(vals) < 1e-9) {
                issues.add("No variance across groups — chart would be degenerate");
            }
        }
        return issues.isEmpty() ? QualityResult.pass() : QualityResult.fail(issues);
    }

    private double variance(double[] values) {
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double var = 0;
        for (double v : values) var += (v - mean) * (v - mean);
        return var / values.length;
    }
}
