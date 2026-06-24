package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.grounding.SemanticGroundingService;
import com.example.BACKEND.catalogue.decision.grounding.SemanticGroundingService.GroundingResult;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.*;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalNarrativeTemplates;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningOrchestrator.ReasoningResult;
import com.example.BACKEND.catalogue.decision.clarification.ClarificationOption;
import com.example.BACKEND.catalogue.decision.clarification.InsufficientEvidenceGuard.EvidenceAssessment;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExplorationPolicy;
import com.example.BACKEND.catalogue.decision.exploration.ProvisionalFindingBuilder;
import com.example.BACKEND.catalogue.decision.exploration.ProvisionalFindingBuilder.ProvisionalResult;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import com.example.BACKEND.catalogue.decision.reasoning.StatisticalInterpretation;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import com.example.BACKEND.catalogue.decision.execution.materialization.AnalyticalResultType;
import com.example.BACKEND.catalogue.decision.presentation.executive.CorrelationExecutivePresenter;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationLayer;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput;
import com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AnalyticalResponseAssembler {

    private static final int MAX_FINDINGS = 3;
    private static final int MAX_METRICS = 2;

    private final VisualizationPlanner visualizationPlanner;
    private final NarrativeCompressor narrativeCompressor;
    private final SemanticGroundingService groundingService;
    private final PresentationLabelResolver labels;
    private final InsightTemplateEngine templateEngine;
    private final FactualLanguageGuard languageGuard;
    private final EvidencePanelBuilder evidencePanelBuilder;
    private final ExecutivePresentationLayer executiveLayer;
    private final ProvisionalFindingBuilder provisionalBuilder;
    private final AnalyticalVerificationOrchestrator verificationOrchestrator;
    private final ResponseModePlanner responseModePlanner;
    private final TableSpecBuilder tableSpecBuilder;
    private final TableResponseBuilder tableResponseBuilder;
    private final AnalyticalNarrativeTemplates narrativeTemplates;

    private final CorrelationExecutivePresenter correlationPresenter;

    public AnalyticalResponseAssembler(
            VisualizationPlanner visualizationPlanner,
            NarrativeCompressor narrativeCompressor,
            SemanticGroundingService groundingService,
            PresentationLabelResolver labels,
            InsightTemplateEngine templateEngine,
            FactualLanguageGuard languageGuard,
            EvidencePanelBuilder evidencePanelBuilder,
            ExecutivePresentationLayer executiveLayer,
            ProvisionalFindingBuilder provisionalBuilder,
            AnalyticalVerificationOrchestrator verificationOrchestrator,
            ResponseModePlanner responseModePlanner,
            TableSpecBuilder tableSpecBuilder,
            TableResponseBuilder tableResponseBuilder,
            AnalyticalNarrativeTemplates narrativeTemplates,
            CorrelationExecutivePresenter correlationPresenter
    ) {
        this.visualizationPlanner = visualizationPlanner;
        this.narrativeCompressor = narrativeCompressor;
        this.groundingService = groundingService;
        this.labels = labels;
        this.templateEngine = templateEngine;
        this.languageGuard = languageGuard;
        this.evidencePanelBuilder = evidencePanelBuilder;
        this.executiveLayer = executiveLayer;
        this.provisionalBuilder = provisionalBuilder;
        this.verificationOrchestrator = verificationOrchestrator;
        this.responseModePlanner = responseModePlanner;
        this.tableSpecBuilder = tableSpecBuilder;
        this.tableResponseBuilder = tableResponseBuilder;
        this.narrativeTemplates = narrativeTemplates;
        this.correlationPresenter = correlationPresenter;
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, null, -1, null, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, -1, null, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore, null, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, null, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, resolvedQuestion, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet results
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, resolvedQuestion, results, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet results,
            EvidenceAssessment evidenceAssessment
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, resolvedQuestion, results,
                evidenceAssessment, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet results,
            EvidenceAssessment evidenceAssessment,
            AnalyticalVerificationOrchestrator.VerificationContext verificationContext
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, resolvedQuestion, results,
                evidenceAssessment, verificationContext, null, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet results,
            EvidenceAssessment evidenceAssessment,
            AnalyticalVerificationOrchestrator.VerificationContext verificationContext,
            ExecutionTrace executionTrace
    ) {
        return assemble(findingsBundle, depthResult, insight, constitution, coverage,
                intent, ranked, evidence, reasoningResult, governanceTrustScore,
                investigationPlan, executionFindings, resolvedQuestion, results,
                evidenceAssessment, verificationContext, executionTrace, null);
    }

    public AnalyticalResponse assemble(
            StructuredFindingsBundle findingsBundle,
            AnalyticalDepthResult depthResult,
            InsightOutput insight,
            ConstitutionReview constitution,
            EvidenceCoverageReport coverage,
            IntentResolution intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ReasoningResult reasoningResult,
            double governanceTrustScore,
            InvestigationPlan investigationPlan,
            ExecutionFindings executionFindings,
            ResolvedAnalyticalQuestion resolvedQuestion,
            com.example.BACKEND.catalogue.decision.contracts.DecisionModels.ComputationResultSet results,
            EvidenceAssessment evidenceAssessment,
            AnalyticalVerificationOrchestrator.VerificationContext verificationContext,
            ExecutionTrace executionTrace,
            AnswerSynthesisOutput answerSynthesis
    ) {
        GroundingResult grounded = groundingService.ground(
                findingsBundle, insight, evidence, constitution, coverage);

        double confidence = governanceTrustScore >= 0
                ? governanceTrustScore
                : grounded.semanticConfidence();
        if (resolvedQuestion != null && resolvedQuestion.confidencePenalty() > 0) {
            confidence = Math.max(0.35, confidence - resolvedQuestion.confidencePenalty());
        }

        List<GroundedAnalyticalFinding> prioritized = reasoningResult != null
                ? reasoningResult.prioritizedFindings()
                : List.of();

        GroundedAnalyticalFinding primary = prioritized.isEmpty() ? null : prioritized.getFirst();
        AnalyticalIntentType intentType = investigationPlan != null
                ? investigationPlan.intentType() : AnalyticalIntentType.GENERAL_ANALYSIS;

        ChartSpec chartSpec = primary != null && primary.chartSpec() != null
                ? primary.chartSpec()
                : (reasoningResult != null && reasoningResult.primaryChart() != null
                        ? reasoningResult.primaryChart()
                        : visualizationPlanner.plan(findingsBundle, depthResult));

        String factualSummary = buildFactualSummary(primary, intentType, confidence);
        String analystSupport = primary != null
                ? narrativeTemplates.support(primary.finding()) : "";
        if (!analystSupport.isBlank()) {
            factualSummary = narrativeCompressor.clean(factualSummary + " " + analystSupport);
        }
        String chartExplanation = primary != null
                ? languageGuard.sanitize(templateEngine.chartCaption(
                        primary.finding(), primary.statistics(), intentType))
                : "";

        InsightBlock insightBlock = new InsightBlock(
                factualTitle(primary, intentType),
                chartExplanation.isBlank()
                        ? narrativeCompressor.compress(factualSummary, 1)
                        : chartExplanation
        );

        List<FindingItem> findings = prioritized.isEmpty()
                ? buildFindings(grounded.groundedFindings(), intentType, confidence)
                : buildFindingsFromGrounded(prioritized, intentType, confidence);

        List<MetricItem> metrics = buildRelevantMetrics(primary, intentType);

        if (findings.isEmpty() && provisionalBuilder.hasGroupedData(executionFindings)) {
            String metricLabel = resolvedQuestion != null && resolvedQuestion.assumption() != null
                    ? resolvedQuestion.assumption().primaryMetricLabel() : "Revenue";
            String grouping = resolvedQuestion != null && resolvedQuestion.assumption() != null
                    ? resolvedQuestion.assumption().grouping() : "segment";
            ProvisionalResult provisional = provisionalBuilder.build(
                    executionFindings, metricLabel, grouping);
            if (provisional.hasContent()) {
                findings = provisional.findings();
                if (metrics.isEmpty()) metrics = provisional.metrics();
            }
        }

        if (chartSpec == null && executionFindings != null
                && executionFindings.materializedResult() != null
                && executionFindings.materializedResult().primaryGrouping() != null) {
            chartSpec = visualizationPlanner.plan(findingsBundle, depthResult);
        }
        if (factualSummary.isBlank() && !findings.isEmpty()) {
            factualSummary = findings.getFirst().summary();
        }

        EvidencePanel panel = evidencePanelBuilder.build(
                primary, investigationPlan, executionFindings, confidence);

        // Ambiguity lowers confidence only — never replace factual summary with planner prose.

        var assumption = resolvedQuestion != null ? resolvedQuestion.assumption() : null;
        List<ClarificationOption> alternatives = resolvedQuestion != null
                ? resolvedQuestion.alternatives() : List.of();
        List<String> available = resolvedQuestion != null
                ? resolvedQuestion.availableMetrics() : List.of();
        var reformulation = resolvedQuestion != null ? resolvedQuestion.suggestedReformulation() : "";

        boolean weakEvidence = evidenceAssessment != null && !evidenceAssessment.strongFindings();
        String recoveryNote = evidenceAssessment != null ? evidenceAssessment.reason() : "";

        ExecutiveInsightCard executiveCard = executiveLayer.present(
                intent, investigationPlan, reasoningResult, chartSpec, confidence,
                false, recoveryNote, resolvedQuestion, results,
                depthResult, executionFindings);

        boolean exploratory = (resolvedQuestion != null && resolvedQuestion.exploratoryMode())
                || weakEvidence;
        String explorationNote = "";

        if (executiveCard != null && executiveCard.visualization() != null && chartSpec == null) {
            chartSpec = executiveCard.visualization();
        }
        if (factualSummary.isBlank() && executiveCard != null && !executiveCard.keyTakeaway().isBlank()) {
            factualSummary = executiveCard.keyTakeaway();
        }
        if (factualSummary.isBlank() && executiveCard != null && !executiveCard.executiveSummary().isBlank()) {
            factualSummary = executiveCard.executiveSummary();
        }

        if (verificationContext != null && executiveCard != null) {
            executiveCard = guardExecutiveCard(executiveCard, verificationContext);
            factualSummary = verificationOrchestrator.guardNarrative(factualSummary, verificationContext);
        }

        var tier = resolvedQuestion != null ? resolvedQuestion.confidenceTier()
                : com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier.MEDIUM;
        var execMode = resolvedQuestion != null ? resolvedQuestion.executionMode()
                : com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode.HYBRID;

        int queryRowCount = results != null ? results.results().size() : 0;
        boolean hasChart = chartSpec != null && chartSpec.getData() != null
                && !chartSpec.getData().isEmpty();
        ResponseMode responseMode = investigationPlan != null
                && investigationPlan.questionDrivenPlan() != null
                && investigationPlan.questionDrivenPlan().visualizationStrategy() != null
                ? investigationPlan.questionDrivenPlan().visualizationStrategy().responseMode()
                : responseModePlanner.plan(investigationPlan, executionFindings, queryRowCount, hasChart);

        com.example.BACKEND.catalogue.decision.semantics.MetricResolution metricResolution =
                investigationPlan != null && investigationPlan.questionDrivenPlan() != null
                        ? investigationPlan.questionDrivenPlan().resolution() : null;
        TableSpec tableSpec = metricResolution != null
                ? tableResponseBuilder.build(executionFindings, metricResolution,
                        factualTitle(primary, intentType))
                : tableSpecBuilder.build(executionFindings, factualTitle(primary, intentType));
        if (responseMode == ResponseMode.KPI) {
            chartSpec = null;
            tableSpec = TableSpec.empty();
        } else if (responseMode == ResponseMode.CHART) {
            tableSpec = TableSpec.empty();
        } else if (responseMode == ResponseMode.TABLE) {
            chartSpec = null;
        }

        String analysisType = null;
        CorrelationAnalysisPayload correlationPayload = null;
        if (executionFindings != null && executionFindings.materializedResult() != null
                && executionFindings.materializedResult().resultType() == AnalyticalResultType.CORRELATION_RESULT
                && executionFindings.materializedResult().correlation() != null) {
            analysisType = "CORRELATION_RESULT";
            correlationPayload = correlationPresenter.toPayload(
                    executionFindings.materializedResult().correlation());
            if (executiveCard != null && executiveCard.visualization() != null) {
                chartSpec = executiveCard.visualization();
            } else if (correlationPayload != null) {
                chartSpec = correlationPresenter.correlationChart(correlationPayload);
            }
            tableSpec = TableSpec.empty();
            metrics = List.of();
            responseMode = ResponseMode.CHART;
        }

        if (answerSynthesis != null && answerSynthesis.hasContent()) {
            factualSummary = languageGuard.sanitize(answerSynthesis.executiveSummary());
            findings = buildFindingsFromSynthesis(answerSynthesis);
            insightBlock = new InsightBlock(
                    insightBlock.title(),
                    narrativeCompressor.compress(factualSummary, 1));
            if (executiveCard != null) {
                executiveCard = new ExecutiveInsightCard(
                        executiveCard.title(),
                        factualSummary,
                        executiveCard.supportingMetrics(),
                        executiveCard.visualization(),
                        answerSynthesis.primaryTakeaway(),
                        executiveCard.confidenceLabel(),
                        answerSynthesis.confidenceExplanation());
            }
        }

        return new AnalyticalResponse(
                factualSummary.isBlank() && executiveCard != null
                        ? executiveCard.keyTakeaway() : factualSummary,
                findings, metrics, chartSpec, insightBlock,
                confidence, panel,
                assumption, alternatives, false, available, reformulation, recoveryNote,
                executiveCard, exploratory, explorationNote, tier, execMode,
                executionTrace, responseMode, tableSpec, analysisType, correlationPayload
        );
    }

    private ExecutiveInsightCard guardExecutiveCard(
            ExecutiveInsightCard card,
            AnalyticalVerificationOrchestrator.VerificationContext ctx
    ) {
        return new ExecutiveInsightCard(
                card.title(),
                verificationOrchestrator.guardNarrative(card.executiveSummary(), ctx),
                card.supportingMetrics(),
                card.visualization(),
                verificationOrchestrator.guardNarrative(card.keyTakeaway(), ctx),
                card.confidenceLabel(),
                verificationOrchestrator.guardNarrative(card.secondaryInterpretation(), ctx)
        );
    }

    private String buildFactualSummary(
            GroundedAnalyticalFinding primary,
            AnalyticalIntentType intentType,
            double confidence
    ) {
        if (primary == null) return "";
        String analyst = narrativeTemplates.headline(primary.finding());
        if (!analyst.isBlank()) return languageGuard.sanitize(analyst);
        String templated = templateEngine.render(
                primary.finding(), primary.statistics(), intentType, confidence);
        if (!templated.isBlank()) return languageGuard.sanitize(templated);
        return languageGuard.sanitize(primary.businessNarrative());
    }

    private String factualTitle(GroundedAnalyticalFinding primary, AnalyticalIntentType intentType) {
        if (primary == null) return "Analysis";
        return switch (primary.finding()) {
            case ContributionFinding c ->
                    labels.resolveMetric(c.metricLabel()) + " by " + labels.resolveDimension(c.dimensionLabel());
            case RankingFinding r ->
                    labels.resolveMetric(r.metricLabel()) + " ranking";
            case ComparativeFinding c ->
                    labels.resolveMetric(c.metricLabel()) + " comparison";
            case TemporalPatternFinding t -> t.temporalDimension() + " trend";
            case EfficiencyFinding e -> e.numeratorLabel() + " efficiency";
            case CorrelationFinding c -> c.sourceVariable() + " vs " + c.targetVariable();
        };
    }

    private List<FindingItem> buildFindingsFromSynthesis(AnswerSynthesisOutput synthesis) {
        List<FindingItem> items = new ArrayList<>();
        if (synthesis.keyFindings() == null) return items;
        for (String finding : synthesis.keyFindings()) {
            if (items.size() >= MAX_FINDINGS) break;
            if (finding == null || finding.isBlank()) continue;
            items.add(new FindingItem(
                    "ANSWER_SYNTHESIS",
                    "Key finding",
                    languageGuard.sanitize(narrativeCompressor.clean(finding)),
                    1.0));
        }
        return items;
    }

    private List<FindingItem> buildFindingsFromGrounded(
            List<GroundedAnalyticalFinding> grounded,
            AnalyticalIntentType intentType,
            double confidence
    ) {
        List<FindingItem> items = new ArrayList<>();
        for (GroundedAnalyticalFinding g : grounded) {
            if (items.size() >= MAX_FINDINGS) break;
            String summary = templateEngine.render(
                    g.finding(), g.statistics(), intentType, confidence);
            if (summary.isBlank()) summary = g.businessNarrative();
            items.add(new FindingItem(
                    g.finding().findingType().name(),
                    findingLabel(g.finding()),
                    languageGuard.sanitize(narrativeCompressor.clean(summary)),
                    g.priorityScore()
            ));
        }
        return items;
    }

    private List<FindingItem> buildFindings(
            List<AnalyticalFinding> findings,
            AnalyticalIntentType intentType,
            double confidence
    ) {
        if (findings == null || findings.isEmpty()) return List.of();
        List<FindingItem> items = new ArrayList<>();
        for (AnalyticalFinding f : findings) {
            if (items.size() >= MAX_FINDINGS) break;
            String summary = templateEngine.render(
                    f, StatisticalInterpretation.none(), intentType, confidence);
            if (summary.isBlank()) summary = findingSummary(f);
            items.add(new FindingItem(
                    f.findingType().name(),
                    findingLabel(f),
                    languageGuard.sanitize(narrativeCompressor.clean(summary)),
                    f.magnitude()
            ));
        }
        return items;
    }

    /** At most 2 metric tiles, only from the primary finding relevant to the question. */
    private List<MetricItem> buildRelevantMetrics(
            GroundedAnalyticalFinding primary,
            AnalyticalIntentType intentType
    ) {
        if (primary == null) return List.of();
        return metricsFromFinding(primary.finding(), intentType).stream()
                .limit(MAX_METRICS)
                .toList();
    }

    private List<MetricItem> metricsFromFinding(AnalyticalFinding f, AnalyticalIntentType intent) {
        AnalyticalIntentType canonical = intent != null ? intent.canonical() : null;
        return switch (f) {
            case ContributionFinding c -> List.of(
                    metric("top_share",
                            labels.resolveSegment(c.topContributor()) + " share",
                            pct(c.topContributorSharePct()), "%", "", "")
            );
            case ComparativeFinding c -> List.of(
                    metric("gap", "Gap", pctSigned(c.deltaPct()), "%", "", "")
            );
            case CorrelationFinding c -> List.of(
                    metric("correlation", "Correlation",
                            String.format(Locale.ROOT, "%.3f", c.correlationCoefficient()), "", "", ""),
                    metric("sample_size", "Sample size",
                            String.format(Locale.ROOT, "%,d", c.sampleSize()), "observations", "", "")
            );
            case TemporalPatternFinding t -> List.of(
                    metric("momentum", "Direction", t.momentum(), "", "", "")
            );
            case RankingFinding r -> canonical == AnalyticalIntentType.RANKING
                    ? List.of(metric("spread", "Leader vs tail",
                    formatNum(r.leaderToTailMultiple()) + "x", "x", "", ""))
                    : List.of();
            case EfficiencyFinding e -> List.of(
                    metric("spread", "Efficiency spread",
                            formatNum(e.efficiencySpread()) + "x", "x", "", "")
            );
        };
    }

    private String findingSummary(AnalyticalFinding f) {
        return switch (f) {
            case ContributionFinding c -> c.executiveSummary();
            case RankingFinding r -> r.executiveSummary();
            case EfficiencyFinding e -> e.executiveSummary();
            case TemporalPatternFinding t -> t.executiveSummary();
            case ComparativeFinding c -> c.executiveSummary();
            case CorrelationFinding c -> c.executiveSummary();
        };
    }

    private String findingLabel(AnalyticalFinding f) {
        return switch (f) {
            case ContributionFinding c ->
                    labels.resolveMetric(c.metricLabel()) + " · " + labels.resolveDimension(c.dimensionLabel());
            case RankingFinding r ->
                    labels.resolveMetric(r.metricLabel()) + " · " + labels.resolveDimension(r.groupingLabel());
            case EfficiencyFinding e -> labels.resolveDimension(e.groupingLabel());
            case TemporalPatternFinding t -> labels.resolveDimension(t.temporalDimension());
            case ComparativeFinding c -> labels.resolveMetric(c.metricLabel());
            case CorrelationFinding c ->
                    c.sourceVariable() + " vs " + c.targetVariable();
        };
    }

    private MetricItem metric(String key, String label, String value, String unit, String delta, String deltaPct) {
        return new MetricItem(key, label, value, unit, delta, deltaPct);
    }

    private String formatNum(double n) {
        if (Math.abs(n) >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000);
        if (Math.abs(n) >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000);
        if (Math.abs(n - Math.rint(n)) < 0.01) return String.format(Locale.ROOT, "%.0f", n);
        return String.format(Locale.ROOT, "%.1f", n);
    }

    private String pct(double n) {
        return String.format(Locale.ROOT, "%.1f", n);
    }

    private String pctSigned(double n) {
        return (n >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f", n);
    }
}
