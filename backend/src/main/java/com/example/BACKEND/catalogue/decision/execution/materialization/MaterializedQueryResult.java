package com.example.BACKEND.catalogue.decision.execution.materialization;

import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.StructuralFinding;

import java.util.List;

/**
 * The full output of {@link AnalyticalQueryMaterializer}.
 *
 * Branches by {@link AnalyticalResultType}:
 *   GROUPED_RESULT     — ranked groupings (entity/dimension breakdown)
 *   CORRELATION_RESULT — CORR aggregate with coefficient, sample size, interpretation
 *   SCALAR_RESULT      — single aggregate value (total, average, etc.)
 */
public record MaterializedQueryResult(
        AnalyticalResultType       resultType,
        List<MaterializedGrouping> groupings,
        MaterializedGrouping       primaryGrouping,
        MaterializedCorrelation    correlation,
        MaterializedScalar         scalar,
        List<StructuralFinding>    findings,
        String                     valueMetricLabel,
        int                        totalRows
) {

    /** A fully executed GROUP BY with ranked entries. */
    public record MaterializedGrouping(
            MaterializationSpec          spec,
            List<MaterializedGroupEntry> rankedEntries,
            double                       totalValueSum,
            double                       giniConcentration,
            int                          groupCount
    ) {
        public boolean hasData() { return rankedEntries != null && !rankedEntries.isEmpty(); }

        public List<MaterializedGroupEntry> top(int n) {
            return rankedEntries.subList(0, Math.min(n, rankedEntries.size()));
        }
    }

    public record MaterializedCorrelation(
            String sourceVariable,
            String targetVariable,
            double correlationCoefficient,
            long   sampleSize,
            String strength,
            String direction,
            String interpretation
    ) {
        public boolean isValid() {
            return !Double.isNaN(correlationCoefficient) && sampleSize > 0;
        }
    }

    public record MaterializedScalar(
            String metricColumn,
            String metricLabel,
            double value,
            Long   supportingCount,
            Double sharePct,
            String segmentLabel
    ) {
        public MaterializedScalar(String metricColumn, String metricLabel, double value, Long supportingCount) {
            this(metricColumn, metricLabel, value, supportingCount, null, null);
        }

        public boolean isValid() {
            return !Double.isNaN(value);
        }
    }

    public static MaterializedQueryResult empty() {
        return new MaterializedQueryResult(
                AnalyticalResultType.GROUPED_RESULT,
                List.of(), null, null, null, List.of(), "value", 0);
    }

    public static MaterializedQueryResult grouped(
            List<MaterializedGrouping> groupings,
            MaterializedGrouping       primary,
            List<StructuralFinding>    findings,
            String                     valueLabel,
            int                        totalRows
    ) {
        return new MaterializedQueryResult(
                AnalyticalResultType.GROUPED_RESULT,
                groupings, primary, null, null, findings, valueLabel, totalRows);
    }

    public static MaterializedQueryResult correlation(
            MaterializedCorrelation correlation,
            List<StructuralFinding> findings,
            String                  valueLabel,
            int                     totalRows
    ) {
        return new MaterializedQueryResult(
                AnalyticalResultType.CORRELATION_RESULT,
                List.of(), null, correlation, null, findings, valueLabel, totalRows);
    }

    public static MaterializedQueryResult scalar(
            MaterializedScalar      scalar,
            List<StructuralFinding> findings,
            String                  valueLabel,
            int                     totalRows
    ) {
        return new MaterializedQueryResult(
                AnalyticalResultType.SCALAR_RESULT,
                List.of(), null, null, scalar, findings, valueLabel, totalRows);
    }

    public boolean hasContent() {
        return switch (resultType) {
            case GROUPED_RESULT -> primaryGrouping != null && primaryGrouping.hasData();
            case CORRELATION_RESULT -> correlation != null && correlation.isValid();
            case SCALAR_RESULT -> scalar != null && scalar.isValid();
        };
    }
}
