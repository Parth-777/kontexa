package com.example.BACKEND.catalogue.semantic.canonical;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares a faithful {@link CanonicalQueryModel} against generated SQL structure.
 */
public final class SqlFidelityReport {

    private SqlFidelityReport() {}

    public record Mutation(String field, String expected, String actual) {}

    public record Result(
            boolean measureMatch,
            boolean aggregationMatch,
            boolean partitionMatch,
            boolean orderingMatch,
            boolean limitMatch,
            boolean relationshipMatch,
            boolean timeGrainMatch,
            List<Mutation> mutations
    ) {
        public boolean allMatch() {
            return measureMatch
                    && aggregationMatch
                    && partitionMatch
                    && orderingMatch
                    && limitMatch
                    && relationshipMatch
                    && timeGrainMatch;
        }
    }

    public static Result compare(CanonicalQueryModel canonical, String generatedSql) {
        SqlInspector.ParsedSql parsed = SqlInspector.parse(generatedSql);
        List<Mutation> mutations = new ArrayList<>();

        boolean measureMatch = checkMeasure(canonical, parsed, generatedSql, mutations);
        boolean aggregationMatch = checkAggregation(canonical, parsed, generatedSql, mutations);
        boolean partitionMatch = checkPartition(canonical, parsed, mutations);
        boolean orderingMatch = checkOrdering(canonical, parsed, mutations);
        boolean limitMatch = checkLimit(canonical, parsed, mutations);
        boolean relationshipMatch = checkRelationship(canonical, parsed, mutations);
        boolean timeGrainMatch = checkTimeGrain(canonical, parsed, mutations);

        return new Result(
                measureMatch,
                aggregationMatch,
                partitionMatch,
                orderingMatch,
                limitMatch,
                relationshipMatch,
                timeGrainMatch,
                List.copyOf(mutations));
    }

    private static boolean checkMeasure(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            String sql,
            List<Mutation> mutations
    ) {
        if (canonical.measure() == null || canonical.measure().column() == null) {
            return true;
        }
        String expected = canonical.measure().column();
        if (isRelationshipIntent(canonical)) {
            if (parsed.corrLeft() != null || parsed.corrRight() != null) {
                boolean inCorr = containsIgnoreCase(parsed.corrLeft(), expected)
                        || containsIgnoreCase(parsed.corrRight(), expected);
                if (inCorr) return true;
            }
        }
        if (parsed.metricColumn() != null && eqIgnoreCase(parsed.metricColumn(), expected)) {
            return true;
        }
        if (sql != null && containsIgnoreCase(sql, expected)) {
            return true;
        }
        mutations.add(new Mutation(
                "measure",
                expected,
                parsed.metricColumn() != null ? parsed.metricColumn() : "absent"));
        return false;
    }

    private static boolean checkAggregation(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            String sql,
            List<Mutation> mutations
    ) {
        if (isRelationshipIntent(canonical)) {
            return true;
        }
        if (canonical.measure() == null) {
            return true;
        }
        String expected = canonical.measure().aggregation();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = SqlInspector.aggregationOnColumn(sql, canonical.measure().column());
        if (actual == null) {
            actual = parsed.metricAggregation();
        }
        if (eqIgnoreCase(expected, actual)) {
            return true;
        }
        mutations.add(new Mutation("aggregation", expected.toUpperCase(Locale.ROOT),
                actual != null ? actual : "absent"));
        return false;
    }

    private static boolean checkPartition(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            List<Mutation> mutations
    ) {
        if (canonical.partition() == null || canonical.partition().column() == null) {
            return true;
        }
        String expected = canonical.partition().column();
        if (parsed.groupByColumns().isEmpty()) {
            mutations.add(new Mutation("partition", expected, "no GROUP BY"));
            return false;
        }
        boolean matched = parsed.groupByColumns().stream()
                .anyMatch(col -> eqIgnoreCase(expected, col)
                        || containsIgnoreCase(col, expected));
        if (matched) {
            return true;
        }
        mutations.add(new Mutation("partition", expected, String.join(",", parsed.groupByColumns())));
        return false;
    }

    private static boolean checkOrdering(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            List<Mutation> mutations
    ) {
        if (canonical.ordering() == null || canonical.ordering().column() == null) {
            return true;
        }
        String expectedCol = canonical.ordering().column();
        String expectedDir = canonical.ordering().direction();
        if (parsed.orderColumn() == null) {
            mutations.add(new Mutation("ordering", orderLabel(expectedCol, expectedDir), "absent"));
            return false;
        }
        boolean colOk = eqIgnoreCase(expectedCol, parsed.orderColumn())
                || containsIgnoreCase(parsed.orderColumn(), expectedCol);
        boolean dirOk = expectedDir == null || expectedDir.isBlank()
                || eqIgnoreCase(expectedDir, parsed.orderDirection());
        if (colOk && dirOk) {
            return true;
        }
        mutations.add(new Mutation(
                "ordering",
                orderLabel(expectedCol, expectedDir),
                orderLabel(parsed.orderColumn(), parsed.orderDirection())));
        return false;
    }

    private static boolean checkLimit(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            List<Mutation> mutations
    ) {
        Integer expected = canonical.limit();
        if (expected == null) {
            return true;
        }
        if (Objects.equals(expected, parsed.limit())) {
            return true;
        }
        mutations.add(new Mutation(
                "limit",
                String.valueOf(expected),
                parsed.limit() != null ? String.valueOf(parsed.limit()) : "absent"));
        return false;
    }

    private static boolean checkRelationship(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            List<Mutation> mutations
    ) {
        CorrExpectation expected = expectedCorrelation(canonical);
        if (expected == null) {
            return true;
        }
        if (parsed.corrLeft() == null || parsed.corrRight() == null) {
            mutations.add(new Mutation(
                    "relationshipOperands",
                    expected.label(),
                    "CORR absent"));
            return false;
        }
        boolean orderedMatch = eqIgnoreCase(expected.left(), parsed.corrLeft())
                && eqIgnoreCase(expected.right(), parsed.corrRight());
        boolean reversedMatch = eqIgnoreCase(expected.left(), parsed.corrRight())
                && eqIgnoreCase(expected.right(), parsed.corrLeft());
        if (orderedMatch || reversedMatch) {
            return true;
        }
        mutations.add(new Mutation(
                "relationshipOperands",
                expected.label(),
                "CORR(" + parsed.corrLeft() + ", " + parsed.corrRight() + ")"));
        return false;
    }

    private static boolean checkTimeGrain(
            CanonicalQueryModel canonical,
            SqlInspector.ParsedSql parsed,
            List<Mutation> mutations
    ) {
        if (canonical.partition() == null) {
            return true;
        }
        String expected = canonical.partition().timeGrain();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (eqIgnoreCase(expected, parsed.dateTruncGrain())) {
            return true;
        }
        mutations.add(new Mutation(
                "timeGrain",
                expected.toUpperCase(Locale.ROOT),
                parsed.dateTruncGrain() != null ? parsed.dateTruncGrain() : "absent"));
        return false;
    }

    private static CorrExpectation expectedCorrelation(CanonicalQueryModel canonical) {
        if (canonical.bivariate() != null
                && canonical.bivariate().columnA() != null
                && canonical.bivariate().columnB() != null) {
            return new CorrExpectation(
                    canonical.bivariate().columnA(),
                    canonical.bivariate().columnB());
        }
        if (canonical.metadata() == null) {
            return null;
        }
        String relationship = canonical.metadata().relationshipVariable();
        String secondary = canonical.metadata().secondaryMetric();
        String metric = canonical.measure() != null ? canonical.measure().column() : null;

        if (relationship != null && secondary != null && !eqIgnoreCase(relationship, secondary)) {
            return new CorrExpectation(relationship, secondary);
        }
        if (metric != null && relationship != null && !eqIgnoreCase(metric, relationship)) {
            return new CorrExpectation(metric, relationship);
        }
        return null;
    }

    private static boolean isRelationshipIntent(CanonicalQueryModel canonical) {
        return canonical.metadata() != null
                && "RELATIONSHIP".equalsIgnoreCase(canonical.metadata().intent());
    }

    private static String orderLabel(String column, String direction) {
        if (column == null) return "absent";
        if (direction == null || direction.isBlank()) {
            return column;
        }
        return column + " " + direction.toUpperCase(Locale.ROOT);
    }

    private static boolean eqIgnoreCase(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private record CorrExpectation(String left, String right) {
        String label() {
            return "CORR(" + left + ", " + right + ")";
        }
    }
}
