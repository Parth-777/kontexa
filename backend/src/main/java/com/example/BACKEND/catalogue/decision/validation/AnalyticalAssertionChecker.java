package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.SqlAssertion;
import com.example.BACKEND.catalogue.decision.validation.AnalyticalTestCase.SqlAssertion.AssertionType;
import com.example.BACKEND.catalogue.decision.validation.ExecutionInspectionLog.AssertionResult;
import com.example.BACKEND.catalogue.decision.validation.ExecutionInspectionLog.StageFailure;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks {@link SqlAssertion}s against a set of generated SQL statements.
 *
 * Each MUST_CONTAIN assertion passes if ANY of the generated SQL strings matches
 * the regex pattern.
 *
 * Each MUST_NOT_CONTAIN assertion passes only if NONE of the SQL strings matches
 * the pattern.
 *
 * Failures are surfaced as {@link StageFailure} objects with exact stage names,
 * so the harness can diagnose which component produced the gap.
 */
@Component
public class AnalyticalAssertionChecker {

    public record CheckResult(
            List<AssertionResult> results,
            List<StageFailure>    failures
    ) {
        public int passed() { return (int) results.stream().filter(AssertionResult::passed).count(); }
        public int failed() { return (int) results.stream().filter(r -> !r.passed()).count(); }
        public boolean allPassed() { return failures.isEmpty(); }
    }

    public CheckResult check(AnalyticalTestCase tc, List<String> generatedSqlStatements) {
        List<AssertionResult> results  = new ArrayList<>();
        List<StageFailure>    failures = new ArrayList<>();

        for (SqlAssertion assertion : tc.sqlAssertions()) {
            AssertionResult result = evaluate(assertion, generatedSqlStatements);
            results.add(result);
            if (!result.passed()) {
                failures.add(toStageFailure(assertion, result, generatedSqlStatements));
            }
        }

        // Structural gap detection — beyond individual assertions
        failures.addAll(detectStructuralGaps(tc, generatedSqlStatements));

        return new CheckResult(results, failures);
    }

    // ─── assertion evaluation ─────────────────────────────────────────────

    private AssertionResult evaluate(SqlAssertion assertion, List<String> sqls) {
        Pattern pat;
        try {
            pat = Pattern.compile(assertion.pattern(), Pattern.DOTALL);
        } catch (Exception e) {
            return new AssertionResult(assertion.id(), assertion.description(), false,
                    null, "Invalid regex pattern: " + e.getMessage());
        }

        if (assertion.type() == AssertionType.MUST_CONTAIN) {
            for (String sql : sqls) {
                if (pat.matcher(sql).find()) {
                    return new AssertionResult(assertion.id(), assertion.description(),
                            true, abbreviate(sql), null);
                }
            }
            return new AssertionResult(assertion.id(), assertion.description(), false, null,
                    "Pattern not found in any of " + sqls.size() + " SQL statements: [" + assertion.pattern() + "]");

        } else { // MUST_NOT_CONTAIN
            for (String sql : sqls) {
                if (pat.matcher(sql).find()) {
                    return new AssertionResult(assertion.id(), assertion.description(), false,
                            abbreviate(sql), "Forbidden pattern found: [" + assertion.pattern() + "]");
                }
            }
            return new AssertionResult(assertion.id(), assertion.description(), true, null, null);
        }
    }

    // ─── structural gap detection ─────────────────────────────────────────

    private List<StageFailure> detectStructuralGaps(AnalyticalTestCase tc, List<String> sqls) {
        List<StageFailure> gaps = new ArrayList<>();

        if (sqls.isEmpty()) {
            gaps.add(new StageFailure("SQL_GENERATION",
                    "NO_SQL_PRODUCED",
                    "SemanticAnalyticalExecutionEngine produced 0 SQL statements for: " + tc.question()));
            return gaps;
        }

        boolean hasGroupBy = sqls.stream().anyMatch(s ->
                s.toUpperCase().contains("GROUP BY"));
        boolean hasAggregate = sqls.stream().anyMatch(s ->
                Pattern.compile("(?i)(SUM|COUNT|AVG|MAX|MIN)\\s*\\(").matcher(s).find());
        boolean hasOrderBy = sqls.stream().anyMatch(s ->
                s.toUpperCase().contains("ORDER BY"));

        if (!hasGroupBy) {
            gaps.add(new StageFailure("PLAN_COMPILATION",
                    "NO_GROUPING_PRODUCED",
                    "No GROUP BY found in any generated SQL — decomposition produced no grouping dimensions. " +
                    "Schema must have produced no ENTITY_DIMENSION or TIME_DIMENSION roles. Question: " + tc.question()));
        }

        if (!hasAggregate) {
            gaps.add(new StageFailure("PLAN_COMPILATION",
                    "NO_AGGREGATION_PRODUCED",
                    "No aggregate function (SUM/COUNT/AVG) found — no value metrics were resolved. " +
                    "Schema resolution may have failed to classify metric columns. Question: " + tc.question()));
        }

        if (!hasOrderBy && requiresRanking(tc)) {
            gaps.add(new StageFailure("PLAN_COMPILATION",
                    "NO_RANKING_PRODUCED",
                    "No ORDER BY found but intent requires ranking. " +
                    "RankingSpec may be missing or pointing to non-existent alias. Question: " + tc.question()));
        }

        // Generic fallback detection — did we produce only a SELECT without grouping?
        boolean hasOnlyGenericSummary = sqls.stream().allMatch(s ->
                !s.toUpperCase().contains("GROUP BY"));
        if (hasOnlyGenericSummary && !sqls.isEmpty()) {
            gaps.add(new StageFailure("DECOMPOSITION",
                    "GENERIC_SUMMARY_FALLBACK",
                    "All generated SQL is ungrouped summary queries (no GROUP BY). " +
                    "This is the failure mode the engine must eliminate. Question: " + tc.question()));
        }

        return gaps;
    }

    private boolean requiresRanking(AnalyticalTestCase tc) {
        return "RANKING".equals(tc.intentCategory())
                || "EFFICIENCY".equals(tc.intentCategory())
                || "CONTRIBUTION".equals(tc.intentCategory());
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private StageFailure toStageFailure(SqlAssertion assertion, AssertionResult result,
                                        List<String> sqls) {
        String stage = inferStage(assertion.id());
        return new StageFailure(stage,
                "ASSERTION_FAILED_" + assertion.id().replace("-", "_"),
                result.failureReason() + " | Generated SQL (" + sqls.size() + " stmts): "
                        + sqls.stream().map(s -> abbreviate(s)).toList());
    }

    private String inferStage(String assertionId) {
        String lower = assertionId.toLowerCase();
        if (lower.contains("s1")) return "SCHEMA_RESOLUTION";
        if (lower.contains("s2") || lower.contains("s3")) return "DECOMPOSITION";
        return "SQL_GENERATION";
    }

    private String abbreviate(String sql) {
        return sql.length() <= 120 ? sql : sql.substring(0, 120) + "...";
    }
}
