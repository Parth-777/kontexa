package com.example.BACKEND.catalogue.decision.exploration;

import com.example.BACKEND.catalogue.decision.candidate.AnalyticalCandidate;
import com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator;
import com.example.BACKEND.catalogue.decision.clarification.AnalyticalAssumption;
import com.example.BACKEND.catalogue.decision.clarification.ClarificationOption;
import com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.IntentResolution;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantic.SemanticAnalysisPlan;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selects best-match interpretation and always allows pipeline execution.
 */
@Service
public class ExploratoryAnalysisPlanner {

    private static final Logger log = LoggerFactory.getLogger(ExploratoryAnalysisPlanner.class);

    private final MultiCandidateInterpretationEngine candidateEngine;
    private final CandidateAnalysisGenerator analysisGenerator;
    private final SoftSemanticValidator softValidator;
    private final MetricFallbackHierarchy fallback;
    private final MetricSemanticRegistry metricRegistry;

    public ExploratoryAnalysisPlanner(
            MultiCandidateInterpretationEngine candidateEngine,
            CandidateAnalysisGenerator analysisGenerator,
            SoftSemanticValidator softValidator,
            MetricFallbackHierarchy fallback,
            MetricSemanticRegistry metricRegistry
    ) {
        this.candidateEngine = candidateEngine;
        this.analysisGenerator = analysisGenerator;
        this.softValidator = softValidator;
        this.fallback = fallback;
        this.metricRegistry = metricRegistry;
    }

    public ResolvedAnalyticalQuestion plan(IntentResolution intent, RegistryResolutionBundle bundle) {
        return plan(intent, bundle, null, null, null);
    }

    public ResolvedAnalyticalQuestion plan(
            IntentResolution intent,
            RegistryResolutionBundle bundle,
            QuestionSemantics semantics,
            MetricResolution metricResolution,
            QuestionDrivenReasoningPlan reasoningPlan
    ) {
        String question = intent.question();
        List<AnalyticalCandidate> analyticalCandidates = analysisGenerator.generate(
                intent, bundle, semantics, metricResolution);
        List<InterpretationCandidatePlan> allPlans = analyticalCandidates.stream()
                .map(AnalyticalCandidate::plan)
                .toList();

        MultiCandidateInterpretationEngine.CandidateSet candidates = candidateEngine.generate(intent, bundle);
        InterpretationCandidatePlan selected = selectBestPlan(
                allPlans, candidates, semantics, metricResolution, reasoningPlan);
        if (selected == null && metricResolution != null && metricResolution.isUsable() && reasoningPlan != null) {
            selected = planFromReasoning(metricResolution, reasoningPlan);
        }
        if (selected == null) {
            selected = new InterpretationCandidatePlan(
                    "Unresolved analysis", question, "total_amount", "Total Revenue",
                    null, "", com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                    com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType.GENERAL_ANALYSIS,
                    0.3, "fallback");
        }

        InterpretationCandidatePlan fallbackPlan = candidates.candidates().size() > 1
                ? candidates.candidates().get(1) : selected;

        String primaryMetric = fallback.resolve(selected.primaryMetric(), bundle);
        String metricLabel = metricRegistry.resolve(primaryMetric)
                .map(m -> m.displayLabel())
                .orElse(selected.primaryMetricLabel());

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Selected: " + selected.label() + " (" + selected.source() + ")");
        assumptions.add("Confidence: " + String.format(Locale.ROOT, "%.2f", selected.confidence()));
        assumptions.addAll(selected.description() != null ? List.of(selected.description()) : List.of());

        AnalyticalAssumption assumption = new AnalyticalAssumption(
                question,
                primaryMetric,
                metricLabel,
                selected.secondaryMetric(),
                selected.grouping() != null ? selected.grouping() : "",
                selected.aggregation(),
                selected.intent().canonical(),
                assumptions,
                selected.confidence(),
                candidates.candidates().size() > 1,
                ""
        );

        SoftSemanticValidator.ValidationOutcome validation = softValidator.validate(
                assumption, bundle, candidates.primarySemantic(), selected);

        HybridExecutionPolicy.HybridPlan hybridPlan = HybridExecutionPolicy.resolve(
                validation.adjustedConfidence(), validation.mode(), selected);

        if (hybridPlan.executionMode() == AnalyticalExecutionMode.EXPLORATORY_HEURISTIC) {
            InterpretationCandidatePlan heuristic = HybridExecutionPolicy.selectForExecution(
                    candidates.candidates(), selected, hybridPlan.executionMode());
            if (!heuristic.label().equals(selected.label())) {
                selected = heuristic;
                primaryMetric = fallback.resolve(selected.primaryMetric(), bundle);
                metricLabel = metricRegistry.resolve(primaryMetric)
                        .map(m -> m.displayLabel())
                        .orElse(selected.primaryMetricLabel());
                assumption = new AnalyticalAssumption(
                        question, primaryMetric, metricLabel,
                        selected.secondaryMetric(), selected.grouping(),
                        selected.aggregation(), selected.intent().canonical(),
                        assumptions, selected.confidence(), true, "");
                validation = softValidator.validate(
                        assumption, bundle, candidates.primarySemantic(), selected);
                hybridPlan = HybridExecutionPolicy.resolve(
                        validation.adjustedConfidence(), validation.mode(), selected);
            }
        }

        boolean weak = hybridPlan.confidenceTier() != PlannerConfidenceTier.HIGH;
        boolean exploratory = hybridPlan.exploratoryMode();
        String explorationNote = hybridPlan.explorationNote();
        if (explorationNote.isBlank() && weak) {
            explorationNote = AnalyticalExplorationPolicy.WEAK_INTERPRETATION_NOTE;
        }

        assumptions.add("Execution: " + hybridPlan.executionMode().name()
                + " (tier=" + hybridPlan.confidenceTier().name() + ")");
        if (exploratory) {
            assumptions.add("Exploratory mode — " + AnalyticalExplorationPolicy.EXPLORATION_STEPS);
        }

        double adjustedConfidence = validation.adjustedConfidence();
        assumption = new AnalyticalAssumption(
                question, primaryMetric, metricLabel,
                selected.secondaryMetric(), selected.grouping(),
                selected.aggregation(), selected.intent().canonical(),
                mergeAssumptions(assumptions, validation.annotations()),
                adjustedConfidence, weak || exploratory, explorationNote
        );

        List<ClarificationOption> alternatives = toClarificationOptions(allPlans, selected);

        log.info("[exploration] selected plan: {} metric={} grouping={} intent={} conf={} tier={} exec={}",
                selected.label(), primaryMetric,
                selected.grouping().isBlank() ? "none" : selected.grouping(),
                selected.intent(), String.format(Locale.ROOT, "%.2f", adjustedConfidence),
                hybridPlan.confidenceTier(), hybridPlan.executionMode());
        log.info("[exploration] fallback plan: {} conf={}",
                fallbackPlan.label(), String.format(Locale.ROOT, "%.2f", fallbackPlan.confidence()));

        SemanticAnalysisPlan semantic = candidates.primarySemantic() != null
                ? candidates.primarySemantic()
                : buildSyntheticSemantic(selected);

        return new ResolvedAnalyticalQuestion(
                assumption,
                alternatives,
                true,
                List.of(),
                fallback.listAvailable(bundle),
                suggestReformulation(assumption, fallback.listAvailable(bundle)),
                semantic,
                allPlans,
                exploratory,
                validation.confidencePenalty(),
                explorationNote,
                hybridPlan.confidenceTier(),
                hybridPlan.executionMode()
        );
    }

    private SemanticAnalysisPlan buildSyntheticSemantic(InterpretationCandidatePlan selected) {
        return new SemanticAnalysisPlan(
                true, selected.confidence(),
                com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.PatternKind.DIMENSION_IMPACT,
                selected.intent(),
                selected.primaryMetric(), selected.primaryMetricLabel(),
                selected.secondaryMetric(),
                selected.grouping().isBlank() ? null : selected.grouping(),
                selected.grouping(),
                null, null, List.of(),
                selected.description(), "");
    }

    private List<String> mergeAssumptions(List<String> base, List<String> annotations) {
        List<String> merged = new ArrayList<>(base);
        merged.addAll(annotations);
        return merged;
    }

    private List<ClarificationOption> toClarificationOptions(
            List<InterpretationCandidatePlan> candidates,
            InterpretationCandidatePlan selected
    ) {
        List<ClarificationOption> options = new ArrayList<>();
        for (InterpretationCandidatePlan c : candidates) {
            if (c.label().equals(selected.label())) continue;
            options.add(new ClarificationOption(
                    c.label(), c.primaryMetric(), c.primaryMetricLabel(),
                    c.grouping(), c.aggregation(), c.intent(), c.description()));
            if (options.size() >= 4) break;
        }
        return options;
    }

    private InterpretationCandidatePlan selectBestPlan(
            List<InterpretationCandidatePlan> candidatePlans,
            MultiCandidateInterpretationEngine.CandidateSet candidates,
            QuestionSemantics semantics,
            MetricResolution resolution,
            QuestionDrivenReasoningPlan reasoningPlan
    ) {
        if (resolution != null && resolution.isUsable() && reasoningPlan != null) {
            return planFromReasoning(resolution, reasoningPlan);
        }
        if (!candidatePlans.isEmpty()) {
            if (semantics != null && semantics.primaryMetric() != null) {
                return candidatePlans.stream()
                        .filter(p -> semantics.primaryMetric().equals(p.primaryMetric())
                                || (p.grouping() != null && semantics.grouping() != null
                                && p.grouping().equals(semantics.grouping())))
                        .findFirst()
                        .orElse(candidates.selected());
            }
            return candidates.selected() != null ? candidates.selected() : candidatePlans.getFirst();
        }
        return candidates.selected();
    }

    private InterpretationCandidatePlan planFromReasoning(
            MetricResolution r, QuestionDrivenReasoningPlan plan
    ) {
        String label = r.primaryMetricLabel() + (r.dimensionLabel() != null && !r.dimensionLabel().isBlank()
                ? " by " + r.dimensionLabel() : " analysis");
        return new InterpretationCandidatePlan(
                label,
                plan.question(),
                r.primaryMetric(),
                r.primaryMetricLabel(),
                r.dimension(),
                r.grouping() != null ? r.grouping() : "",
                com.example.BACKEND.catalogue.decision.governance.MetricSemanticTypes.AggregationType.SUM,
                plan.intent(),
                r.confidence(),
                "reasoning_planner");
    }

    private String suggestReformulation(AnalyticalAssumption assumption, List<String> available) {
        if (assumption.grouping() == null || assumption.grouping().isBlank()) {
            return "Try: 'What share of " + assumption.primaryMetricLabel()
                    + " contributes to total revenue?'";
        }
        return String.format(Locale.ROOT,
                "Try: 'What is %s by %s?'",
                assumption.primaryMetricLabel().toLowerCase(Locale.ROOT),
                assumption.grouping().replace('_', ' '));
    }
}
