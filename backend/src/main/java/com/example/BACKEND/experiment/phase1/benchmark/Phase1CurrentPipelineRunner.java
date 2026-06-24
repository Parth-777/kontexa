package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.util.List;

/**
 * Runs the existing deterministic semantic pipeline for benchmark comparison.
 */
public final class Phase1CurrentPipelineRunner {

    private final QuestionSemanticExtractor extractor;
    private final MetricResolutionEngine metricEngine;
    private final QuestionInvestigationPlanner investigationPlanner;
    private final UniversalAnalysisPlanner analysisPlanner;
    private final DeterministicAnalyticalQueryPlanner sqlPlanner;

    public Phase1CurrentPipelineRunner(
            QuestionSemanticExtractor extractor,
            MetricResolutionEngine metricEngine,
            QuestionInvestigationPlanner investigationPlanner,
            UniversalAnalysisPlanner analysisPlanner,
            DeterministicAnalyticalQueryPlanner sqlPlanner
    ) {
        this.extractor = extractor;
        this.metricEngine = metricEngine;
        this.investigationPlanner = investigationPlanner;
        this.analysisPlanner = analysisPlanner;
        this.sqlPlanner = sqlPlanner;
    }

    public Phase1PipelineRun run(String question, RegistryResolutionBundle bundle) {
        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());
        List<QuerySpec> specs = plan.executable() ? sqlPlanner.plan(plan, bundle) : List.of();
        String metric = plan.primaryMetric();
        String dimension = plan.dimension();
        return new Phase1PipelineRun(
                metric, dimension, List.of(), specs, plan.executable(),
                plan.executable() ? null : plan.blockingReason());
    }

    public record Phase1PipelineRun(
            String metric,
            String dimension,
            List<Phase1FilterSpec> filters,
            List<QuerySpec> querySpecs,
            boolean executable,
            String blockingReason
    ) {}
}
