package com.example.BACKEND.catalogue.semantic.canonical;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.StructuredPlanProjection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares a faithful {@link CanonicalQueryModel} against the mutating {@link AnalysisPlan}
 * (and optional generated SQL) to surface planner-field preservation.
 */
public final class SemanticFidelityReport {

    private static final Pattern SQL_LIMIT = Pattern.compile(
            "\\bLIMIT\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_AGG = Pattern.compile(
            "\\b(SUM|AVG|COUNT|MIN|MAX)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private SemanticFidelityReport() {}

    public record Result(
            boolean measurePreserved,
            boolean aggregationPreserved,
            boolean partitionPreserved,
            boolean orderingPreserved,
            boolean limitPreserved,
            boolean relationshipOperandsPreserved,
            boolean timeGrainPreserved,
            List<String> mutations
    ) {
        public boolean allPreserved() {
            return measurePreserved
                    && aggregationPreserved
                    && partitionPreserved
                    && orderingPreserved
                    && limitPreserved
                    && relationshipOperandsPreserved
                    && timeGrainPreserved;
        }
    }

    public static Result compare(
            CanonicalQueryModel canonical,
            AnalysisPlan analysisPlan,
            List<QuerySpec> querySpecs
    ) {
        List<String> mutations = new ArrayList<>();
        StructuredPlanProjection projection = analysisPlan != null
                ? analysisPlan.structuredProjection()
                : StructuredPlanProjection.empty();

        boolean measurePreserved = checkMeasure(canonical, analysisPlan, mutations);
        boolean aggregationPreserved = checkAggregation(canonical, projection, querySpecs, mutations);
        boolean partitionPreserved = checkPartition(canonical, analysisPlan, projection, mutations);
        boolean orderingPreserved = checkOrdering(canonical, projection, mutations);
        boolean limitPreserved = checkLimit(canonical, projection, querySpecs, mutations);
        boolean relationshipOperandsPreserved = checkRelationshipOperands(canonical, analysisPlan, mutations);
        boolean timeGrainPreserved = checkTimeGrain(canonical, projection, mutations);

        return new Result(
                measurePreserved,
                aggregationPreserved,
                partitionPreserved,
                orderingPreserved,
                limitPreserved,
                relationshipOperandsPreserved,
                timeGrainPreserved,
                List.copyOf(mutations));
    }

    private static boolean checkMeasure(
            CanonicalQueryModel canonical,
            AnalysisPlan plan,
            List<String> mutations
    ) {
        String expected = canonical.measure() != null ? canonical.measure().column() : null;
        String actual = plan != null ? plan.primaryMetric() : null;
        if (eqIgnoreCase(expected, actual)) {
            return true;
        }
        mutations.add("measure: planner=" + expected + " analysisPlan=" + actual);
        return false;
    }

    private static boolean checkAggregation(
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection,
            List<QuerySpec> querySpecs,
            List<String> mutations
    ) {
        String expected = canonical.measure() != null ? canonical.measure().aggregation() : null;
        if (expected == null || expected.isBlank()) {
            return true;
        }

        String projectionAgg = projection != null ? projection.primaryAggregation() : null;
        boolean projectionOk = eqIgnoreCase(expected, projectionAgg);

        String sqlAgg = extractSqlAggregation(querySpecs);
        boolean sqlOk = sqlAgg == null || eqIgnoreCase(expected, sqlAgg);

        if (projectionOk && sqlOk) {
            return true;
        }
        if (!projectionOk) {
            mutations.add("aggregation: planner=" + expected + " projection=" + projectionAgg);
        }
        if (!sqlOk) {
            mutations.add("aggregation: planner=" + expected + " sql=" + sqlAgg);
        }
        return false;
    }

    private static boolean checkPartition(
            CanonicalQueryModel canonical,
            AnalysisPlan plan,
            StructuredPlanProjection projection,
            List<String> mutations
    ) {
        String expected = canonical.partition() != null ? canonical.partition().column() : null;
        String actual = plan != null ? plan.dimension() : null;

        boolean primaryOk = expected == null || eqIgnoreCase(expected, actual);

        List<String> plannerDims = canonical.metadata() != null
                ? canonical.metadata().dimensions()
                : List.of();
        List<String> projectionDims = projection != null ? projection.dimensions() : List.of();
        boolean allDimsOk = Objects.equals(plannerDims, projectionDims);

        if (primaryOk && allDimsOk) {
            return true;
        }
        if (!primaryOk) {
            mutations.add("partition: planner=" + expected + " analysisPlan.dimension=" + actual);
        }
        if (!allDimsOk) {
            mutations.add("dimensions: planner=" + plannerDims + " projection=" + projectionDims);
        }
        return false;
    }

    private static boolean checkOrdering(
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection,
            List<String> mutations
    ) {
        if (canonical.ordering() == null) {
            return true;
        }
        String expectedCol = canonical.ordering().column();
        String expectedDir = canonical.ordering().direction();
        String actualCol = projection != null ? projection.orderColumn() : null;
        String actualDir = projection != null ? projection.orderDirection() : null;

        if (eqIgnoreCase(expectedCol, actualCol) && eqIgnoreCase(expectedDir, actualDir)) {
            return true;
        }
        mutations.add("ordering: planner=" + expectedCol + " " + expectedDir
                + " projection=" + actualCol + " " + actualDir);
        return false;
    }

    private static boolean checkLimit(
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection,
            List<QuerySpec> querySpecs,
            List<String> mutations
    ) {
        Integer expected = canonical.limit();
        if (expected == null) {
            return true;
        }

        Integer projectionLimit = projection != null ? projection.resultLimit() : null;
        boolean projectionOk = Objects.equals(expected, projectionLimit);

        Integer sqlLimit = extractSqlLimit(querySpecs);
        boolean sqlOk = sqlLimit == null || Objects.equals(expected, sqlLimit);

        if (projectionOk && sqlOk) {
            return true;
        }
        if (!projectionOk) {
            mutations.add("limit: planner=" + expected + " projection=" + projectionLimit);
        }
        if (!sqlOk) {
            mutations.add("limit: planner=" + expected + " sql=" + sqlLimit);
        }
        return false;
    }

    private static boolean checkRelationshipOperands(
            CanonicalQueryModel canonical,
            AnalysisPlan plan,
            List<String> mutations
    ) {
        if (canonical.bivariate() == null) {
            return true;
        }

        String expectedA = canonical.bivariate().columnA();
        String expectedB = canonical.bivariate().columnB();
        String actualA = plan != null ? plan.primaryMetric() : null;
        String actualB = plan != null ? plan.relationshipVariable() : null;

        if (eqIgnoreCase(expectedA, actualA) && eqIgnoreCase(expectedB, actualB)) {
            return true;
        }
        mutations.add("relationshipOperands: planner=(" + expectedA + "," + expectedB
                + ") analysisPlan=(" + actualA + "," + actualB + ")");
        return false;
    }

    private static boolean checkTimeGrain(
            CanonicalQueryModel canonical,
            StructuredPlanProjection projection,
            List<String> mutations
    ) {
        String expected = canonical.partition() != null ? canonical.partition().timeGrain() : null;
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = projection != null ? projection.timeGrain() : null;
        if (eqIgnoreCase(expected, actual)) {
            return true;
        }
        mutations.add("timeGrain: planner=" + expected + " projection=" + actual);
        return false;
    }

    private static String extractSqlAggregation(List<QuerySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        Matcher m = SQL_AGG.matcher(specs.get(0).sql());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static Integer extractSqlLimit(List<QuerySpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        Matcher m = SQL_LIMIT.matcher(specs.get(0).sql());
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean eqIgnoreCase(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }
}
