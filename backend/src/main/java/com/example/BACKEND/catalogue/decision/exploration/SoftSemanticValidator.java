package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.clarification.AnalyticalAssumption;
import com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy;
import com.example.BACKEND.catalogue.decision.clarification.QueryViabilityChecker;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Soft validation — applies confidence penalties instead of hard rejection.
 */
@Component
public class SoftSemanticValidator {

    public enum ValidationMode {
        CONFIDENT,
        CONFIDENCE_PENALTY,
        EXPLORATORY
    }

    public record ValidationOutcome(
            ValidationMode mode,
            double         confidencePenalty,
            double         adjustedConfidence,
            List<String>   annotations,
            boolean        allowExecution
    ) {
        public static ValidationOutcome confident(double confidence) {
            return new ValidationOutcome(ValidationMode.CONFIDENT, 0, confidence, List.of(), true);
        }
    }

    private final QueryViabilityChecker viabilityChecker;
    private final MetricFallbackHierarchy fallback;

    public SoftSemanticValidator(
            QueryViabilityChecker viabilityChecker,
            MetricFallbackHierarchy fallback
    ) {
        this.viabilityChecker = viabilityChecker;
        this.fallback = fallback;
    }

    public ValidationOutcome validate(
            AnalyticalAssumption assumption,
            RegistryResolutionBundle bundle,
            SemanticAnalysisPlan semantic,
            InterpretationCandidatePlan candidate
    ) {
        List<String> annotations = new ArrayList<>();
        double penalty = 0;
        double base = assumption.resolutionConfidence();

        QueryViabilityChecker.ViabilityResult hard = viabilityChecker.check(assumption, bundle, semantic);
        if (!hard.viable()) {
            for (String issue : hard.issues()) {
                annotations.add("Soft validation note: " + issue);
            }
            penalty += 0.2;
        }

        if (candidate != null && candidate.confidence() < 0.65) {
            annotations.add("Low-confidence interpretation — exploratory execution");
            penalty += 0.15;
        }

        if (assumption.grouping() == null || assumption.grouping().isBlank()) {
            if (assumption.aggregation().name().equals("RATIO")) {
                annotations.add("Composition ratio analysis — no grouping required");
            } else {
                annotations.add("Grouping inferred heuristically");
                penalty += 0.1;
            }
        }

        String metric = assumption.primaryMetric();
        if (!fallback.metricAvailable(metric, bundle) && bundle != null) {
            annotations.add("Metric resolved via fallback hierarchy");
            penalty += 0.1;
        }

        double adjusted = Math.max(0.35, base - penalty);
        ValidationMode mode = penalty <= 0.05 ? ValidationMode.CONFIDENT
                : (candidate != null && candidate.confidence() < 0.6
                        ? ValidationMode.EXPLORATORY : ValidationMode.CONFIDENCE_PENALTY);

        return new ValidationOutcome(mode, penalty, adjusted, annotations, true);
    }
}
