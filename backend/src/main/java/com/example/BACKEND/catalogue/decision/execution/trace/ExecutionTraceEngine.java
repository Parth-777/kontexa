package com.example.BACKEND.catalogue.decision.execution.trace;

import com.example.BACKEND.catalogue.decision.execution.repair.RepairOutcome;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.transforms.TransformationStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and updates per-request execution traces for analyst-grade transparency.
 */
@Component
public class ExecutionTraceEngine {

    public static final String UNDERSTAND_QUESTION     = "understand_question";
    public static final String DETECT_INTENT             = "detect_intent";
    public static final String RESOLVE_METRIC_DIMENSION = "resolve_metric_dimension";
    public static final String BUILD_AGGREGATION_PLAN  = "build_aggregation_plan";
    public static final String EXECUTE_WAREHOUSE       = "execute_warehouse";
    public static final String VALIDATE_RESULTS        = "validate_results";
    public static final String GENERATE_VISUALIZATION  = "generate_visualization";
    public static final String SYNTHESIZE_INSIGHT      = "synthesize_insight";

    private final ConcurrentHashMap<java.util.UUID, ExecutionTrace> active = new ConcurrentHashMap<>();

    public ExecutionTrace begin(java.util.UUID runId) {
        ExecutionTrace trace = seedPipeline();
        active.put(runId, trace);
        return trace;
    }

    /** Seeds trace steps from a question-specific reasoning plan. */
    public ExecutionTrace beginFromPlan(java.util.UUID runId, QuestionDrivenReasoningPlan plan) {
        ExecutionTrace trace = ExecutionTrace.start();
        if (plan != null && plan.reasoningSteps() != null) {
            for (QuestionDrivenReasoningPlan.ReasoningStep s : plan.reasoningSteps()) {
                trace = trace.withStep(ExecutionTraceStep.pending(s.stepKey(), s.title()));
            }
        } else {
            trace = seedPipeline();
        }
        active.put(runId, trace);
        return trace;
    }

    public ExecutionTrace injectTransformationSteps(
            java.util.UUID runId, java.util.List<TransformationStep> steps
    ) {
        if (steps == null || steps.isEmpty()) return get(runId);
        ExecutionTrace trace = get(runId);
        for (TransformationStep ts : steps) {
            boolean exists = trace.steps().stream()
                    .anyMatch(s -> s.stepKey().equals(ts.stepKey()));
            if (!exists) {
                trace = trace.withStep(ExecutionTraceStep.pending(ts.stepKey(), ts.title()));
            }
            ExecutionTraceStep step = findStep(trace, ts.stepKey());
            if (step != null) {
                trace = trace.withStep(step.running(ts.toDetails()));
                trace = trace.withStep(findStep(trace, ts.stepKey()).completed(0, ts.toDetails()));
            }
        }
        active.put(runId, trace);
        return trace;
    }

    /** Appends real SQL execution/repair steps from warehouse attempts. */
    public ExecutionTrace appendRepairOutcomes(java.util.UUID runId, List<RepairOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return get(runId);
        ExecutionTrace trace = get(runId);
        for (RepairOutcome outcome : outcomes) {
            if (outcome.traceSteps() == null) continue;
            for (ExecutionTraceStep step : outcome.traceSteps()) {
                boolean exists = trace.steps().stream()
                        .anyMatch(s -> s.stepKey().equals(step.stepKey()));
                if (!exists) {
                    trace = trace.withStep(ExecutionTraceStep.pending(step.stepKey(), step.title()));
                }
                trace = trace.withStep(step);
            }
        }
        active.put(runId, trace);
        return trace;
    }

    public List<String> stepKeys(java.util.UUID runId) {
        ExecutionTrace trace = get(runId);
        List<String> keys = new ArrayList<>();
        for (ExecutionTraceStep s : trace.steps()) keys.add(s.stepKey());
        return keys;
    }

    public ExecutionTrace get(java.util.UUID runId) {
        return active.getOrDefault(runId, seedPipeline());
    }

    public ExecutionTrace startStep(java.util.UUID runId, String stepKey, Map<String, Object> details) {
        ExecutionTrace trace = get(runId);
        ExecutionTraceStep step = findStep(trace, stepKey);
        if (step == null) return trace;
        trace = trace.withStep(step.running(details));
        active.put(runId, trace);
        return trace;
    }

    public ExecutionTrace completeStep(java.util.UUID runId, String stepKey, long ms, Map<String, Object> details) {
        ExecutionTrace trace = get(runId);
        ExecutionTraceStep step = findStep(trace, stepKey);
        if (step == null) return trace;
        trace = trace.withStep(step.completed(ms, details));
        active.put(runId, trace);
        return trace;
    }

    public ExecutionTrace failStep(java.util.UUID runId, String stepKey, long ms, String error) {
        ExecutionTrace trace = get(runId);
        ExecutionTraceStep step = findStep(trace, stepKey);
        if (step == null) return trace;
        trace = trace.withStep(step.failed(ms, error));
        active.put(runId, trace);
        return trace;
    }

    public ExecutionTrace finish(java.util.UUID runId) {
        ExecutionTrace trace = get(runId).complete();
        active.put(runId, trace);
        return trace;
    }

    private ExecutionTrace seedPipeline() {
        ExecutionTrace trace = ExecutionTrace.start();
        trace = trace.withStep(ExecutionTraceStep.pending(UNDERSTAND_QUESTION,
                "Understanding question"));
        trace = trace.withStep(ExecutionTraceStep.pending(DETECT_INTENT,
                "Detecting analytical intent"));
        trace = trace.withStep(ExecutionTraceStep.pending(RESOLVE_METRIC_DIMENSION,
                "Resolving metric and dimension"));
        trace = trace.withStep(ExecutionTraceStep.pending(BUILD_AGGREGATION_PLAN,
                "Building aggregation plan"));
        trace = trace.withStep(ExecutionTraceStep.pending(EXECUTE_WAREHOUSE,
                "Executing warehouse query"));
        trace = trace.withStep(ExecutionTraceStep.pending(VALIDATE_RESULTS,
                "Validating results"));
        trace = trace.withStep(ExecutionTraceStep.pending(GENERATE_VISUALIZATION,
                "Generating visualization"));
        trace = trace.withStep(ExecutionTraceStep.pending(SYNTHESIZE_INSIGHT,
                "Synthesizing insight"));
        return trace;
    }

    private ExecutionTraceStep findStep(ExecutionTrace trace, String key) {
        return trace.steps().stream()
                .filter(s -> s.stepKey().equals(key))
                .findFirst()
                .orElse(null);
    }
}
