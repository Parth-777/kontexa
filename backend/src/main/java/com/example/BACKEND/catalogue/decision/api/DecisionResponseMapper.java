package com.example.BACKEND.catalogue.decision.api;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.InsightOutput;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.FindingItem;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse.MetricItem;
import com.example.BACKEND.catalogue.decision.presentation.EvidencePanel;
import com.example.BACKEND.catalogue.decision.presentation.FactualLanguageGuard;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveConfidenceLabel;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveSupportingMetric;
import com.example.BACKEND.catalogue.decision.presentation.executive.PrescriptiveContentGate;
import com.example.BACKEND.catalogue.decision.presentation.executive.PresentationDebugMode;
import com.example.BACKEND.catalogue.decision.execution.repair.ExecutionDiagnosticSession;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisProperties;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticShadowResponseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DecisionResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(DecisionResponseMapper.class);

    private final PrescriptiveContentGate prescriptiveGate;
    private final ExecutionDiagnosticSession diagnosticSession;
    private final SemanticShadowResponseMapper shadowResponseMapper;
    private final AnswerSynthesisProperties answerSynthesisProperties;

    public DecisionResponseMapper(
            PrescriptiveContentGate prescriptiveGate,
            ExecutionDiagnosticSession diagnosticSession,
            SemanticShadowResponseMapper shadowResponseMapper,
            AnswerSynthesisProperties answerSynthesisProperties
    ) {
        this.prescriptiveGate = prescriptiveGate;
        this.diagnosticSession = diagnosticSession;
        this.shadowResponseMapper = shadowResponseMapper;
        this.answerSynthesisProperties = answerSynthesisProperties;
    }

    public Map<String, Object> toRunResponse(UUID runId, DecisionRunResult result) {
        return toRunResponse(runId, result, Map.of());
    }

    public Map<String, Object> toRunResponse(
            UUID runId, DecisionRunResult result, Map<String, Object> requestMeta
    ) {
        InsightOutput insight = result.insight();
        AnalyticalResponse analytical = result.analytical();
        boolean debug = PresentationDebugMode.enabled(requestMeta);

        ExecutiveInsightCard executive = analytical.executiveCard() != null
                ? analytical.executiveCard()
                : fallbackExecutive(analytical);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId.toString());
        m.put("status", "COMPLETED");
        m.put("insightId", insight.insightId());

        // Executive presentation (primary user surface)
        m.put("executive_card", mapExecutiveCard(executive));
        if (analytical.analysisType() != null) {
            m.put("analysis_type", analytical.analysisType());
        }
        if (analytical.correlationAnalysis() != null) {
            m.put("correlation_analysis", analytical.correlationAnalysis().toMap());
        }
        m.put("executive_summary", executive.executiveSummary());
        m.put("title", executive.title());
        m.put("key_takeaway", executive.keyTakeaway());
        m.put("confidence_label", executive.confidenceLabel().display());
        m.put("metrics", mapExecutiveMetrics(executive.supportingMetrics()));
        m.put("chart_spec", mapChartSpec(executive.visualization()));
        m.put("insight", Map.of(
                "title", executive.title(),
                "text", executive.secondaryInterpretation().isBlank()
                        ? executive.executiveSummary()
                        : executive.secondaryInterpretation()
        ));
        m.put("response_mode", analytical.responseMode() != null
                ? analytical.responseMode().name() : "MIXED");
        if (analytical.executionTrace() != null) {
            m.put("execution_trace", analytical.executionTrace().toMap());
        }
        if (analytical.tableSpec() != null && analytical.tableSpec().hasContent()) {
            m.put("table_spec", analytical.tableSpec().toMap());
        }
        m.put("evidence_panel", mapEvidencePanel(analytical.evidencePanel()));
        m.put("recovery_mode", analytical.recoveryMode());
        m.put("exploratory_mode", analytical.exploratoryMode());
        m.put("confidence_tier", analytical.confidenceTier() != null
                ? analytical.confidenceTier().name() : "MEDIUM");
        m.put("execution_mode", analytical.executionMode() != null
                ? analytical.executionMode().name() : "HYBRID");
        if (debug && analytical.assumption() != null) {
            m.put("resolution_confidence", analytical.assumption().resolutionConfidence());
        }

        boolean prescriptive = prescriptiveGate.allowPrescriptiveContent(
                analytical, analytical.confidence())
                && executive.confidenceLabel() == ExecutiveConfidenceLabel.HIGH;

        m.put("actions", prescriptive ? insight.actions() : Collections.emptyList());
        m.put("evidenceRefs", insight.evidenceRefs());
        m.put("strategicImplications", prescriptive ? insight.strategicImplications() : Collections.emptyList());
        m.put("operationalRisks", prescriptive ? insight.operationalRisks() : Collections.emptyList());
        m.put("businessCauses", prescriptive ? insight.businessCauses() : Collections.emptyList());
        m.put("prioritizationRationale", prescriptive ? insight.prioritizationRationale() : "");
        m.put("narrative", executive.executiveSummary());
        m.put("execution_path", buildExecutionPath(runId, result));

        Map<String, Object> semanticPlanner = mapSemanticPlanner(runId);
        if (!semanticPlanner.isEmpty()) {
            m.put("semantic_planner", semanticPlanner);
        }

        m.put("answer_synthesis", mapAnswerSynthesis(result.answerSynthesis()));

        if (result.executivePresentation() != null && result.executivePresentation().hasContent()) {
            m.put("presentation", result.executivePresentation().toMap());
        }

        if (result.executiveTable() != null && result.executiveTable().hasContent()) {
            m.put("executive_table", result.executiveTable().toMap());
        }

        if (result.semanticShadow() != null) {
            m.put("semantic_shadow", shadowResponseMapper.toMap(result.semanticShadow()));
        }

        if (debug) {
            m.put("presentation_debug", true);
            m.put("confidence", analytical.confidence());
            if (result.verification() != null) {
                m.put("query_debug_panel", result.verification().debugPanel().toMap());
                m.put("verification_passed", result.verification().report().passed());
                m.put("verification_violations", result.verification().report().violations());
                var conf = result.verification().confidence();
                m.put("confidence_decomposition", Map.of(
                        "sql_validity", conf.sqlValidity(),
                        "aggregation_consistency", conf.aggregationConsistency(),
                        "statistical_separation", conf.statisticalSeparation(),
                        "row_coverage", conf.rowCoverage(),
                        "narrative_certainty", conf.narrativeCertainty(),
                        "composite", conf.composite()
                ));
            }
            m.put("findings", mapFindings(analytical.findings()));
            m.put("analytical_assumption", mapAssumption(analytical.assumption()));
            m.put("clarification_options", mapClarifications(analytical.clarificationOptions()));
            m.put("available_metrics", analytical.availableMetrics());
            m.put("suggested_reformulation", analytical.suggestedReformulation());
            m.put("recovery_reason", analytical.recoveryReason());
        }

        return m;
    }

    private Map<String, Object> buildExecutionPath(UUID runId, DecisionRunResult result) {
        Map<String, Object> path = new LinkedHashMap<>();
        Map<String, Object> facts = diagnosticSession.warehouseFacts(runId);

        log.info("[execution-path] runId={} mapper={} session={} facts_empty={} facts_identity={}",
                runId,
                System.identityHashCode(this),
                System.identityHashCode(diagnosticSession),
                facts.isEmpty(),
                System.identityHashCode(facts));

        if (!facts.isEmpty()) {
            path.put("question", facts.getOrDefault("question", ""));
            path.put("served_path", facts.getOrDefault("served_path", ""));
            path.put("planner_mode", facts.getOrDefault("planner_mode", ""));
            path.put("resolved_metric", facts.getOrDefault("resolved_metric", ""));
            path.put("resolved_dimension", facts.getOrDefault("resolved_dimension", ""));
            path.put("investigation_plan", facts.getOrDefault("investigation_plan", ""));
            path.put("materialized_query", facts.getOrDefault("materialized_query", ""));
            path.put("first_empty_stage", facts.getOrDefault("first_empty_stage", ""));
            path.put("generated_sql", facts.getOrDefault("generated_sql", ""));
            path.put("warehouse_row_count", facts.getOrDefault("warehouse_row_count", 0));
            path.put("sample_rows", facts.getOrDefault("sample_rows", List.of()));
            path.put("bigquery_error", facts.getOrDefault("bigquery_error", ""));
            path.put("rows_discarded_by_validation", facts.getOrDefault("rows_discarded_by_validation", false));
            path.put("validation_discard_reason", facts.getOrDefault("validation_discard_reason", ""));
            path.put("materialized_rows", facts.getOrDefault("materialized_rows", List.of()));
            path.put("materialization_failure_reason", facts.getOrDefault("materialization_failure_reason", ""));
            log.info("[execution-path] runId={} populated row_count={} sample_rows={} sql_len={}",
                    runId,
                    path.get("warehouse_row_count"),
                    path.get("sample_rows") instanceof List<?> rows ? rows.size() : 0,
                    path.get("generated_sql") != null ? path.get("generated_sql").toString().length() : 0);
            return path;
        }

        log.warn("[execution-path] runId={} falling back to empty defaults — warehouse facts never recorded",
                runId);

        if (result.verification() != null && result.verification().debugPanel() != null
                && !result.verification().debugPanel().generatedSql().isEmpty()) {
            var primary = result.verification().debugPanel().generatedSql().getFirst();
            path.put("generated_sql", primary.sql() != null ? primary.sql() : "");
            path.put("warehouse_row_count", primary.rowCount());
            path.put("sample_rows", primary.sampleRows() != null ? primary.sampleRows() : List.of());
            path.put("bigquery_error", primary.failureReason() != null ? primary.failureReason() : "");
            path.put("rows_discarded_by_validation", false);
            path.put("validation_discard_reason", "");
        } else {
            path.put("generated_sql", "");
            path.put("warehouse_row_count", 0);
            path.put("sample_rows", List.of());
            path.put("bigquery_error", "");
            path.put("rows_discarded_by_validation", false);
            path.put("validation_discard_reason", "");
        }
        return path;
    }

    private Map<String, Object> mapSemanticPlanner(UUID runId) {
        Map<String, Object> facts = diagnosticSession.warehouseFacts(runId);
        if (facts.isEmpty()) return Map.of();
        Map<String, Object> planner = new LinkedHashMap<>();
        copyIfPresent(facts, planner, "planner_mode", "mode");
        copyIfPresent(facts, planner, "served_path", "served_path");
        copyIfPresent(facts, planner, "resolved_metric", "metric");
        copyIfPresent(facts, planner, "resolved_dimension", "dimension");
        copyIfPresent(facts, planner, "resolved_intent", "intent");
        copyIfPresent(facts, planner, "resolved_relationship_variable", "relationship_variable");
        copyIfPresent(facts, planner, "gpt_confidence", "confidence");
        copyIfPresent(facts, planner, "gpt_validation_valid", "validation_valid");
        copyIfPresent(facts, planner, "gpt_validation_issues", "validation_issues");
        return planner;
    }

    private Map<String, Object> mapAnswerSynthesis(AnswerSynthesisOutput synthesis) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("mode", answerSynthesisProperties.getMode().name());
        if (synthesis == null || !synthesis.hasContent()) {
            block.put("applied", false);
            return block;
        }
        block.put("applied", true);
        block.put("answer_type", synthesis.answerType());
        block.put("suggested_visualization", synthesis.suggestedVisualization());
        block.put("executive_summary", synthesis.executiveSummary());
        block.put("key_findings", synthesis.keyFindings());
        block.put("confidence_explanation", synthesis.confidenceExplanation());
        if (synthesis.followUpQuestions() != null && !synthesis.followUpQuestions().isEmpty()) {
            block.put("follow_up_questions", synthesis.followUpQuestions());
        }
        return block;
    }

    private static void copyIfPresent(
            Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey
    ) {
        if (source.containsKey(sourceKey) && source.get(sourceKey) != null) {
            target.put(targetKey, source.get(sourceKey));
        }
    }

    private ExecutiveInsightCard fallbackExecutive(AnalyticalResponse analytical) {
        return new ExecutiveInsightCard(
                analytical.insight() != null ? analytical.insight().title() : "Analysis",
                analytical.executiveSummary(),
                List.of(),
                analytical.chartSpec(),
                analytical.executiveSummary(),
                ExecutiveConfidenceLabel.fromScore(analytical.confidence(), analytical.recoveryMode()),
                analytical.insight() != null ? analytical.insight().text() : ""
        );
    }

    private Map<String, Object> mapExecutiveCard(ExecutiveInsightCard card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", card.title());
        m.put("executive_summary", card.executiveSummary());
        m.put("supporting_metrics", mapExecutiveMetrics(card.supportingMetrics()));
        m.put("visualization", mapChartSpec(card.visualization()));
        m.put("key_takeaway", card.keyTakeaway());
        m.put("confidence_label", card.confidenceLabel().display());
        m.put("secondary_interpretation", card.secondaryInterpretation());
        return m;
    }

    private List<Map<String, Object>> mapExecutiveMetrics(List<ExecutiveSupportingMetric> metrics) {
        if (metrics == null) return List.of();
        return metrics.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", m.label());
            row.put("value", m.value());
            row.put("unit", m.unit());
            row.put("context", m.context());
            return row;
        }).toList();
    }

    private Map<String, Object> mapAssumption(
            com.example.BACKEND.catalogue.decision.clarification.AnalyticalAssumption a
    ) {
        if (a == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interpreted_question", a.interpretedQuestion());
        m.put("primary_metric", a.primaryMetric());
        m.put("primary_metric_label", a.primaryMetricLabel());
        m.put("secondary_metric", a.secondaryMetric());
        m.put("grouping", a.grouping());
        m.put("aggregation", a.aggregation().name());
        m.put("intent", a.intent().name());
        m.put("assumptions", a.assumptions());
        m.put("resolution_confidence", a.resolutionConfidence());
        m.put("ambiguous", a.ambiguous());
        m.put("ambiguity_note", a.ambiguityNote());
        return m;
    }

    private List<Map<String, Object>> mapClarifications(
            List<com.example.BACKEND.catalogue.decision.clarification.ClarificationOption> options
    ) {
        if (options == null) return List.of();
        return options.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", o.label());
            m.put("primary_metric", o.primaryMetric());
            m.put("metric_label", o.metricLabel());
            m.put("grouping", o.grouping());
            m.put("aggregation", o.aggregation().name());
            m.put("intent", o.intent().name());
            m.put("description", o.description());
            return m;
        }).toList();
    }

    private Map<String, Object> mapEvidencePanel(EvidencePanel panel) {
        if (panel == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric_used", panel.metricUsed());
        m.put("grouping_used", panel.groupingUsed());
        m.put("aggregation_method", panel.aggregationMethod());
        m.put("sample_size", panel.sampleSize());
        m.put("confidence_basis", panel.confidenceBasis());
        return m;
    }

    private List<Map<String, Object>> mapFindings(List<FindingItem> findings) {
        return findings.stream().map(f -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", f.type());
            row.put("label", f.label());
            row.put("summary", f.summary());
            row.put("magnitude", f.magnitude());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> mapMetrics(List<MetricItem> metrics) {
        return metrics.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", m.key());
            row.put("label", m.label());
            row.put("value", m.value());
            row.put("unit", m.unit());
            row.put("delta", m.delta());
            row.put("deltaPct", m.deltaPct());
            return row;
        }).toList();
    }

    private Map<String, Object> mapChartSpec(ChartSpec spec) {
        if (spec == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", spec.getType() != null ? spec.getType().name() : "BAR");
        m.put("title", spec.getTitle());
        m.put("subtitle", spec.getSubtitle());
        m.put("categoryKey", spec.getCategoryKey());
        m.put("valueKey", spec.getValueKey());
        m.put("xKey", spec.getXKey());
        m.put("yKey", spec.getYKey());
        m.put("valueFormat", spec.getValueFormat());
        m.put("xFormat", spec.getXFormat());
        m.put("data", spec.getData());
        return m;
    }
}
