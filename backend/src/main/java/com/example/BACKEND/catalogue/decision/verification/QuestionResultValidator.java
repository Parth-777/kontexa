package com.example.BACKEND.catalogue.decision.verification;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalIntentKind;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;
import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rejects responses when query results do not match question entities.
 */
@Component
public class QuestionResultValidator {

    private final SemanticTransformationEngine transformationEngine;

    public QuestionResultValidator(SemanticTransformationEngine transformationEngine) {
        this.transformationEngine = transformationEngine;
    }

    public record ValidationResult(
            boolean passed,
            List<String> violations,
            boolean shouldRetry,
            SemanticTransformationResult recoveryTransform
    ) {
        public ValidationResult(boolean passed, List<String> violations, boolean shouldRetry) {
            this(passed, violations, shouldRetry, null);
        }
    }

    public ValidationResult validate(
            QuestionSemantics semantics,
            MetricResolution resolution,
            ExecutionFindings findings,
            QuestionDrivenReasoningPlan plan
    ) {
        List<String> violations = new ArrayList<>();

        if (resolution != null && resolution.rejected()) {
            violations.add(resolution.rejectionReason());
            return new ValidationResult(false, violations, true);
        }

        if (semantics.primaryMetric() != null && resolution != null) {
            String expected = semantics.primaryMetric().toLowerCase(Locale.ROOT);
            String actual = resolution.primaryMetric() != null
                    ? resolution.primaryMetric().toLowerCase(Locale.ROOT) : "";
            if (!actual.contains(expected) && !expected.contains(actual)
                    && semantics.confidence() >= 0.6) {
                violations.add("Metric mismatch: asked " + semantics.primaryMetric()
                        + " but resolved " + resolution.primaryMetric());
            }
        }

        if (semantics.hasDimension() && resolution != null && resolution.dimension() != null) {
            String expectedDim = semantics.dimension().toLowerCase(Locale.ROOT);
            String actualDim = resolution.dimension().toLowerCase(Locale.ROOT);
            boolean distanceExpected = expectedDim.contains("distance");
            boolean distanceActual = actualDim.contains("distance");
            if (!distanceExpected && distanceActual) {
                violations.add("Dimension mismatch: question does not mention distance but got "
                        + resolution.dimension());
            } else if (!actualDim.contains(expectedDim) && !expectedDim.contains(actualDim)
                    && semantics.confidence() >= 0.6) {
                violations.add("Dimension mismatch: asked " + semantics.dimension()
                        + " but resolved " + resolution.dimension());
            }
        }

        if (findings != null && findings.materializedResult() != null
                && findings.materializedResult().primaryGrouping() != null) {
            var entries = findings.materializedResult().primaryGrouping().rankedEntries();
            if (entries != null && !entries.isEmpty()) {
                String dimName = entries.getFirst().dimensionName();
                if (semantics.hasDimension() && dimName != null) {
                    String expected = semantics.dimension().toLowerCase(Locale.ROOT);
                    String actual = dimName.toLowerCase(Locale.ROOT);
                    if (!actual.contains(expected) && !expected.contains(actual)
                            && !expected.contains("weekend") && actual.contains("distance")) {
                        violations.add("Result dimension " + dimName
                                + " does not match question entity " + semantics.dimension());
                    }
                }
            }
        }

        if (semantics.primaryMetric() != null && semantics.primaryMetric().contains("tip")) {
            Set<String> forbidden = Set.of("trip_distance", "trip_distance_bucket");
            if (resolution != null && resolution.dimension() != null
                    && forbidden.contains(resolution.dimension())) {
                violations.add("Tip question incorrectly grouped by trip distance");
            }
        }

        boolean shouldRetry = !violations.isEmpty();
        return new ValidationResult(violations.isEmpty(), violations, shouldRetry);
    }

    public ValidationResult validateWithTransformation(
            QuestionSemantics semantics,
            MetricResolution resolution,
            ExecutionFindings findings,
            QuestionDrivenReasoningPlan plan,
            RegistryResolutionBundle bundle,
            String tableRef
    ) {
        ValidationResult base = validate(semantics, resolution, findings, plan);
        if (base.passed() || base.recoveryTransform() != null) return base;

        if (resolution != null && resolution.dimension() != null) {
            SemanticTransformationResult recovery = transformationEngine.transformWithFallbacks(
                    semantics.question(), tableRef,
                    resolution.primaryMetric(), resolution.dimension(), resolution.grouping(),
                    AnalyticalIntentKind.DISTRIBUTION, "recovery", bundle);
            if (recovery.success()) {
                List<String> notes = new ArrayList<>(base.violations());
                notes.add("Recovered via semantic transformation: " + recovery.dimension().concept());
                return new ValidationResult(false, notes, true, recovery);
            }
        }
        return base;
    }
}
