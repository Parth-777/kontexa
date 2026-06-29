package com.example.BACKEND.catalogue.decision.investigation;

import com.example.BACKEND.catalogue.charts.ChartSpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.InsightOutput;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QueryResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.AnalyticalSqlExecutionService;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.decision.exploration.AnalyticalExecutionMode;
import com.example.BACKEND.catalogue.decision.exploration.PlannerConfidenceTier;
import com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse;
import com.example.BACKEND.catalogue.decision.presentation.EvidencePanel;
import com.example.BACKEND.catalogue.decision.presentation.ResponseMode;
import com.example.BACKEND.catalogue.decision.presentation.TableSpec;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveConfidenceLabel;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutiveInsightCard;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentation;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationBuilder;
import com.example.BACKEND.catalogue.decision.presentation.executive.ExecutivePresentationStatisticsBuilder;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisInput;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesisOutput;
import com.example.BACKEND.catalogue.decision.synthesis.answer.AnswerSynthesizer;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;
import com.example.BACKEND.catalogue.semantic.phase2.SemanticCatalogueFactory;
import com.example.BACKEND.catalogue.service.CatalogueApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Investigation Runtime V1 — CHANGE-mode, dimension-driver analysis.
 *
 * <p>Sibling execution path selected after planning when the planner classifies a question
 * as INVESTIGATION. Reuses the existing warehouse execution, canonical SQL rendering,
 * answer synthesis, and executive presentation infrastructure. Produces an auditable
 * {@link EvidencePack} and a {@link NarrativeOutput}, then assembles a {@link DecisionRunResult}.
 *
 * <p>Scope is deliberately minimal: additive (SUM/COUNT) measures, dimension drivers only.
 * Metric drivers, the metric relationship graph, LMDI, associative drivers, strategy
 * synthesis, and the churn/forecast playbooks are intentionally not implemented.
 */
@Component
public class DriverAnalysisRuntime {

    private static final Logger log = LoggerFactory.getLogger(DriverAnalysisRuntime.class);

    private final CatalogueApprovalService catalogueApprovalService;
    private final ObjectMapper mapper;
    private final InvestigationProperties properties;
    private final InvestigationSpecBuilder specBuilder;
    private final ChangeWindowPolicy windowPolicy;
    private final InvestigationQuerySetBuilder querySetBuilder;
    private final AnalyticalSqlExecutionService sqlExecutionService;
    private final ChangeConfirmer changeConfirmer;
    private final DimensionContributionAnalyzer contributionAnalyzer;
    private final DriverRanker driverRanker;
    private final EvidencePackAssembler evidencePackAssembler;
    private final AnswerSynthesizer answerSynthesizer;
    private final ExecutivePresentationBuilder presentationBuilder;
    private final ExecutivePresentationStatisticsBuilder statisticsBuilder;

    public DriverAnalysisRuntime(
            CatalogueApprovalService catalogueApprovalService,
            ObjectMapper mapper,
            InvestigationProperties properties,
            InvestigationSpecBuilder specBuilder,
            ChangeWindowPolicy windowPolicy,
            InvestigationQuerySetBuilder querySetBuilder,
            AnalyticalSqlExecutionService sqlExecutionService,
            ChangeConfirmer changeConfirmer,
            DimensionContributionAnalyzer contributionAnalyzer,
            DriverRanker driverRanker,
            EvidencePackAssembler evidencePackAssembler,
            AnswerSynthesizer answerSynthesizer,
            ExecutivePresentationBuilder presentationBuilder,
            ExecutivePresentationStatisticsBuilder statisticsBuilder
    ) {
        this.catalogueApprovalService = catalogueApprovalService;
        this.mapper = mapper;
        this.properties = properties;
        this.specBuilder = specBuilder;
        this.windowPolicy = windowPolicy;
        this.querySetBuilder = querySetBuilder;
        this.sqlExecutionService = sqlExecutionService;
        this.changeConfirmer = changeConfirmer;
        this.contributionAnalyzer = contributionAnalyzer;
        this.driverRanker = driverRanker;
        this.evidencePackAssembler = evidencePackAssembler;
        this.answerSynthesizer = answerSynthesizer;
        this.presentationBuilder = presentationBuilder;
        this.statisticsBuilder = statisticsBuilder;
    }

    public DecisionRunResult run(
            UUID runId,
            String question,
            String tenantId,
            GptPlanningOutcome gptOutcome,
            RegistryResolutionBundle bundle,
            ExecutionTrace trace
    ) {
        CanonicalQueryModel model = gptOutcome.canonicalQueryModel();
        ApprovedCatalogueSnapshot catalogue = loadCatalogue(tenantId, bundle);
        String direction = gptOutcome.semanticPlan() != null
                ? gptOutcome.semanticPlan().investigationDirection() : null;
        double planConfidence = gptOutcome.semanticPlan() != null
                ? gptOutcome.semanticPlan().confidence() : 0.5;

        // Stage 1 — resolve base (applicability, measure, time column, candidate dimensions)
        InvestigationSpecBuilder.BaseResolution base =
                specBuilder.resolveBase(question, model, direction, catalogue, properties);
        if (!base.applicable()) {
            log.info("[investigation] run={} not applicable: {}", runId, base.inapplicableReason());
            return simpleNarrative(runId, trace,
                    "This question was routed for investigation, but a driver analysis is not "
                            + "available for it: " + base.inapplicableReason() + ".");
        }

        // Stage 2 — probe latest data date, derive change windows
        QuerySpec boundsSpec = querySetBuilder.boundsSpec(base);
        List<QueryResult> boundsResults = sqlExecutionService.executeTemplateBatch(
                List.of(boundsSpec), question, tenantId, runId);
        LocalDate latestDate = latestDate(boundsResults, InvestigationQuerySetBuilder.BOUNDS_KEY, base.timeColumn());
        if (latestDate == null) {
            log.info("[investigation] run={} no datable rows for time column {}", runId, base.timeColumn());
            return simpleNarrative(runId, trace,
                    "No dated rows were available to establish a comparison window for this metric.");
        }
        ChangeWindowPolicy.Windows windows = windowPolicy.derive(base.timeColumn(), base.grain(), latestDate);
        InvestigationSpec spec = specBuilder.finalize(base, windows);
        log.info("[investigation] run={} metric={} grain={} baseline={}..{} observation={}..{} dims={}",
                runId, spec.targetMeasure().column(), spec.grain(),
                windows.baseline().startInclusive(), windows.baseline().endExclusive(),
                windows.observation().startInclusive(), windows.observation().endExclusive(),
                spec.candidateDimensions().size());

        // Stage 3 — build + execute the investigation query set
        InvestigationQuerySetBuilder.QuerySet querySet =
                querySetBuilder.build(spec, properties.getMaxMembersPerDimension());
        List<QueryResult> results = sqlExecutionService.executeTemplateBatch(
                querySet.specs(), question, tenantId, runId);

        // Stage 4 — confirm the headline change
        String metricCol = spec.targetMeasure().column();
        double baselineValue = scalarValue(results, querySet.headlineBaselineKey(), metricCol);
        double observationValue = scalarValue(results, querySet.headlineObservationKey(), metricCol);
        MetricChange change = changeConfirmer.confirm(
                metricCol, baselineValue, observationValue,
                querySet.headlineBaselineKey(), querySet.headlineObservationKey(),
                properties.getMaterialityThresholdPct());
        log.info("[investigation] run={} change baseline={} observation={} delta={} pct={} material={}",
                runId, baselineValue, observationValue, change.absoluteDelta(),
                change.percentDelta(), change.material());

        if (!change.material()) {
            return simpleNarrative(runId, trace, String.format(
                    "%s did not change materially between the compared periods "
                            + "(%.2f vs %.2f, %.1f%%). No driver decomposition was performed.",
                    humanize(metricCol), baselineValue, observationValue, change.percentDelta()));
        }

        // Stage 5 — per-dimension evidence + contribution decomposition + ranking
        List<DimensionEvidence> evidence = buildEvidence(spec, querySet, results, metricCol);
        List<DriverContribution> contributions = contributionAnalyzer.analyze(change, evidence);
        DriverRanker.Result ranking = driverRanker.rank(
                contributions, change.absoluteDelta(), properties.getTopDriversInPack());

        // Stage 6 — assemble Evidence Pack
        EvidencePack pack = evidencePackAssembler.assemble(
                spec, change, ranking, evidence, querySet.specs(), results, querySet.meanings());
        logPack(runId, pack);

        // Stage 7 — presentation (lead driver dimension) + narrative
        ExecutivePresentation presentation = buildPresentation(spec, ranking, querySet, results, planConfidence);
        NarrativeOutput narrative = synthesizeNarrative(
                question, spec, change, ranking, presentation, model, planConfidence, runId);

        return buildResult(runId, trace, change, ranking, pack, presentation, narrative);
    }

    // ─────────────────────────────────────────────────────
    // Evidence assembly
    // ─────────────────────────────────────────────────────

    private List<DimensionEvidence> buildEvidence(
            InvestigationSpec spec,
            InvestigationQuerySetBuilder.QuerySet querySet,
            List<QueryResult> results,
            String metricCol
    ) {
        List<DimensionEvidence> evidence = new ArrayList<>();
        for (CandidateDimension dim : spec.candidateDimensions()) {
            String baseKey = querySet.dimensionBaselineKeys().get(dim.column());
            String obsKey = querySet.dimensionObservationKeys().get(dim.column());
            Map<String, Double> baseMap = membersMap(results, baseKey, dim.column(), metricCol);
            Map<String, Double> obsMap = membersMap(results, obsKey, dim.column(), metricCol);

            Set<String> union = new LinkedHashSet<>();
            union.addAll(obsMap.keySet());
            union.addAll(baseMap.keySet());

            List<DimensionEvidence.MemberValue> members = new ArrayList<>();
            for (String member : union) {
                members.add(new DimensionEvidence.MemberValue(
                        member,
                        baseMap.getOrDefault(member, 0.0),
                        obsMap.getOrDefault(member, 0.0)));
            }
            evidence.add(new DimensionEvidence(
                    dim.column(), dim.label(), members, members.size(), baseKey, obsKey));
        }
        return evidence;
    }

    // ─────────────────────────────────────────────────────
    // Presentation
    // ─────────────────────────────────────────────────────

    private ExecutivePresentation buildPresentation(
            InvestigationSpec spec,
            DriverRanker.Result ranking,
            InvestigationQuerySetBuilder.QuerySet querySet,
            List<QueryResult> results,
            double confidence
    ) {
        if (ranking.rankedDrivers().isEmpty()) {
            return null;
        }
        try {
            String leadDim = ranking.rankedDrivers().getFirst().contribution().dimensionColumn();
            String obsKey = querySet.dimensionObservationKeys().get(leadDim);
            List<Map<String, Object>> rows = rowsFor(results, obsKey);
            if (rows.isEmpty()) {
                return null;
            }
            CanonicalQueryModel leadModel = new CanonicalQueryModel(
                    spec.targetMeasure(),
                    new CanonicalQueryModel.PartitionSpec(leadDim, null),
                    List.of(),
                    null, null,
                    new CanonicalQueryModel.OrderSpec(spec.targetMeasure().column(), "DESC"),
                    null,
                    new CanonicalQueryModel.PlannerMetadata(
                            "RANKING", confidence, "driver decomposition", List.of(leadDim), null, null));
            ExecutivePresentation presentation = presentationBuilder.build(leadModel, rows);
            return statisticsBuilder.enrich(presentation, leadModel, rows);
        } catch (Exception e) {
            log.warn("[investigation] presentation build failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────
    // Narrative (reuses AnswerSynthesizer / GptAnswerSynthesizer)
    // ─────────────────────────────────────────────────────

    private NarrativeOutput synthesizeNarrative(
            String question,
            InvestigationSpec spec,
            MetricChange change,
            DriverRanker.Result ranking,
            ExecutivePresentation presentation,
            CanonicalQueryModel model,
            double confidence,
            UUID runId
    ) {
        List<Map<String, Object>> evidenceRows = evidenceRows(spec, change, ranking);
        AnswerSynthesisInput input = new AnswerSynthesisInput(
                question,
                "",
                evidenceRows,
                new AnswerSynthesisInput.MetricMetadata(
                        spec.targetMeasure().column(),
                        humanize(spec.targetMeasure().column()),
                        spec.targetMeasure().aggregation()),
                leadDimensionMetadata(ranking),
                confidence,
                new AnswerSynthesisInput.ExecutionMetadata(
                        runId != null ? runId.toString() : "",
                        evidenceRows.size(),
                        "INVESTIGATION",
                        true),
                model,
                presentation,
                null);

        Optional<AnswerSynthesisOutput> synthesized = answerSynthesizer.synthesize(input);
        if (synthesized.isPresent() && synthesized.get().hasContent()) {
            AnswerSynthesisOutput out = synthesized.get();
            return new NarrativeOutput(
                    out.executiveSummary(),
                    out.keyFindings(),
                    out.confidenceExplanation(),
                    out.followUpQuestions(),
                    true);
        }
        return deterministicNarrative(spec, change, ranking);
    }

    private NarrativeOutput deterministicNarrative(
            InvestigationSpec spec, MetricChange change, DriverRanker.Result ranking
    ) {
        String metric = humanize(spec.targetMeasure().column());
        String dirWord = MetricChange.INCREASE.equals(change.observedDirection()) ? "increased" : "decreased";
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("%s %s by %.1f%% (%+.2f) between the compared periods.",
                capitalize(metric), dirWord, Math.abs(change.percentDelta()), change.absoluteDelta()));
        List<String> findings = new ArrayList<>();
        for (RankedDriver d : ranking.rankedDrivers()) {
            DriverContribution c = d.contribution();
            findings.add(String.format("%s = %s contributed %+.2f (%.1f%% of the change).",
                    c.dimensionLabel(), c.member(), c.absoluteContribution(), c.contributionPct()));
        }
        if (!ranking.rankedDrivers().isEmpty()) {
            DriverContribution top = ranking.rankedDrivers().getFirst().contribution();
            summary.append(String.format(" The largest driver was %s = %s (%.1f%% of the change).",
                    top.dimensionLabel(), top.member(), top.contributionPct()));
        }
        for (RankedDriver d : ranking.counterEvidence()) {
            DriverContribution c = d.contribution();
            findings.add(String.format("Counter-evidence: %s = %s moved %+.2f, opposing the overall change.",
                    c.dimensionLabel(), c.member(), c.absoluteContribution()));
        }
        String confidence = String.format("Ranked drivers explain %.1f%% of the change; %.1f%% unexplained.",
                ranking.explainedPct(), ranking.residualPct());
        return new NarrativeOutput(summary.toString(), findings, confidence, List.of(), false);
    }

    private List<Map<String, Object>> evidenceRows(
            InvestigationSpec spec, MetricChange change, DriverRanker.Result ranking
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("driver", "TOTAL " + spec.targetMeasure().column());
        headline.put("baseline", change.baselineValue());
        headline.put("observation", change.observationValue());
        headline.put("delta", change.absoluteDelta());
        headline.put("share_pct", 100.0);
        rows.add(headline);
        for (RankedDriver d : ranking.rankedDrivers()) {
            DriverContribution c = d.contribution();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("driver", c.dimensionLabel() + " = " + c.member());
            row.put("baseline", c.baselineValue());
            row.put("observation", c.observationValue());
            row.put("delta", c.absoluteContribution());
            row.put("share_pct", c.contributionPct());
            rows.add(row);
        }
        return rows;
    }

    private AnswerSynthesisInput.DimensionMetadata leadDimensionMetadata(DriverRanker.Result ranking) {
        if (ranking.rankedDrivers().isEmpty()) {
            return new AnswerSynthesisInput.DimensionMetadata("", "");
        }
        DriverContribution top = ranking.rankedDrivers().getFirst().contribution();
        return new AnswerSynthesisInput.DimensionMetadata(top.dimensionColumn(), top.dimensionLabel());
    }

    // ─────────────────────────────────────────────────────
    // Result assembly
    // ─────────────────────────────────────────────────────

    private DecisionRunResult buildResult(
            UUID runId,
            ExecutionTrace trace,
            MetricChange change,
            DriverRanker.Result ranking,
            EvidencePack pack,
            ExecutivePresentation presentation,
            NarrativeOutput narrative
    ) {
        String summary = narrative.executiveSummary();
        List<String> businessCauses = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        for (RankedDriver d : ranking.rankedDrivers()) {
            DriverContribution c = d.contribution();
            businessCauses.add(String.format("%s = %s (%.1f%%)",
                    c.dimensionLabel(), c.member(), c.contributionPct()));
            evidenceRefs.add(c.specKey());
        }

        InsightOutput insight = new InsightOutput(
                runId.toString(),
                "Investigation",
                summary,
                List.of(),
                evidenceRefs,
                List.of(),
                List.of(),
                businessCauses,
                narrative.confidenceExplanation());

        ChartSpec chartSpec = null;
        TableSpec tableSpec = TableSpec.empty();
        ResponseMode responseMode = ResponseMode.MIXED;
        EvidencePanel evidencePanel = EvidencePanel.empty();
        ExecutiveInsightCard card;

        if (presentation != null && presentation.hasContent()) {
            chartSpec = presentationBuilder.toChartSpec(presentation, rowsFromPresentationSource(presentation));
            tableSpec = presentationBuilder.toTableSpec(presentation);
            responseMode = presentationBuilder.responseMode(presentation);
            card = new ExecutiveInsightCard(
                    "Investigation",
                    summary,
                    presentationBuilder.toSupportingMetrics(presentation),
                    chartSpec,
                    summary,
                    confidenceLabel(pack),
                    String.join(" ", narrative.keyFindings()));
            evidencePanel = new EvidencePanel(
                    pack.investigation().metricColumn(),
                    presentation.summary() != null ? presentation.summary().partitionLabel() : "",
                    pack.investigation().metricAggregation(),
                    pack.coverage().membersConsidered(),
                    "Investigation evidence pack");
        } else {
            card = ExecutiveInsightCard.empty(summary, confidenceLabel(pack));
        }

        AnalyticalResponse analytical = new AnalyticalResponse(
                summary,
                List.of(),
                List.of(),
                chartSpec,
                new AnalyticalResponse.InsightBlock("Investigation", summary),
                0.85,
                evidencePanel,
                null,
                List.of(),
                false,
                List.of(),
                "",
                "",
                card,
                false,
                "",
                PlannerConfidenceTier.MEDIUM,
                AnalyticalExecutionMode.HYBRID,
                trace,
                responseMode,
                tableSpec,
                presentation != null ? presentation.type() : "INVESTIGATION",
                null);

        AnswerSynthesisOutput synthesis = new AnswerSynthesisOutput(
                summary,
                narrative.keyFindings(),
                narrative.confidenceExplanation(),
                "INVESTIGATION",
                "INVESTIGATION",
                narrative.followUpQuestions());

        return new DecisionRunResult(insight, analytical, null, null, synthesis, presentation, null);
    }

    private DecisionRunResult simpleNarrative(UUID runId, ExecutionTrace trace, String message) {
        InsightOutput insight = new InsightOutput(
                runId.toString(), "Investigation", message,
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
        AnalyticalResponse analytical = new AnalyticalResponse(
                message,
                List.of(),
                List.of(),
                null,
                new AnalyticalResponse.InsightBlock("Investigation", message),
                0.5,
                EvidencePanel.empty(),
                null,
                List.of(),
                false,
                List.of(),
                "",
                "",
                ExecutiveInsightCard.empty(message, ExecutiveConfidenceLabel.LIMITED),
                false,
                "",
                PlannerConfidenceTier.LOW,
                AnalyticalExecutionMode.HYBRID,
                trace,
                ResponseMode.KPI,
                TableSpec.empty(),
                "INVESTIGATION",
                null);
        return new DecisionRunResult(insight, analytical, null, null, null, null, null);
    }

    private static ExecutiveConfidenceLabel confidenceLabel(EvidencePack pack) {
        return switch (pack.confidence().level()) {
            case "HIGH" -> ExecutiveConfidenceLabel.HIGH;
            case "LOW" -> ExecutiveConfidenceLabel.LIMITED;
            default -> ExecutiveConfidenceLabel.MODERATE;
        };
    }

    // The presentation was built from the lead-dimension observation rows; re-derive the same
    // row shape from the presentation table so the cardinality reducer has category/value rows.
    private static List<Map<String, Object>> rowsFromPresentationSource(ExecutivePresentation presentation) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (presentation == null || presentation.table() == null || presentation.table().rows() == null) {
            return rows;
        }
        for (Map<String, String> formatted : presentation.table().rows()) {
            rows.add(new LinkedHashMap<>(formatted));
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────
    // Catalogue + row extraction helpers
    // ─────────────────────────────────────────────────────

    private ApprovedCatalogueSnapshot loadCatalogue(String tenantId, RegistryResolutionBundle bundle) {
        try {
            String snapshotJson = catalogueApprovalService.getApprovedSnapshot(tenantId);
            JsonNode node = mapper.readTree(snapshotJson);
            return SemanticCatalogueFactory.catalogueFrom(node, bundle);
        } catch (Exception e) {
            log.warn("[investigation] catalogue snapshot unavailable for tenant={}, using bundle fallback: {}",
                    tenantId, e.getMessage());
            return SemanticCatalogueFactory.catalogueFrom(null, bundle);
        }
    }

    private static List<Map<String, Object>> rowsFor(List<QueryResult> results, String key) {
        if (key == null) {
            return List.of();
        }
        for (QueryResult r : results) {
            if (key.equals(r.key())) {
                return r.rows() != null ? r.rows() : List.of();
            }
        }
        return List.of();
    }

    private static double scalarValue(List<QueryResult> results, String key, String column) {
        List<Map<String, Object>> rows = rowsFor(results, key);
        if (rows.isEmpty()) {
            return 0.0;
        }
        return toDouble(rows.getFirst().get(column));
    }

    private static Map<String, Double> membersMap(
            List<QueryResult> results, String key, String dimColumn, String measureColumn
    ) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rowsFor(results, key)) {
            Object memberRaw = row.get(dimColumn);
            String member = memberRaw != null ? String.valueOf(memberRaw) : "(null)";
            double value = toDouble(row.get(measureColumn));
            map.merge(member, value, Double::sum);
        }
        return map;
    }

    private static LocalDate latestDate(List<QueryResult> results, String key, String column) {
        List<Map<String, Object>> rows = rowsFor(results, key);
        if (rows.isEmpty()) {
            return null;
        }
        return toLocalDate(rows.getFirst().get(column));
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (v instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (v instanceof java.util.Date date) {
            return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        int sep = s.indexOf('T');
        if (sep < 0) {
            sep = s.indexOf(' ');
        }
        String datePart = sep > 0 ? s.substring(0, sep) : (s.length() >= 10 ? s.substring(0, 10) : s);
        try {
            return LocalDate.parse(datePart);
        } catch (Exception e) {
            return null;
        }
    }

    private static double toDouble(Object v) {
        if (v == null) {
            return 0.0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).replace(",", "").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void logPack(UUID runId, EvidencePack pack) {
        try {
            log.info("[investigation] run={} evidence_pack={}", runId, mapper.writeValueAsString(pack));
        } catch (Exception e) {
            log.info("[investigation] run={} evidence_pack (unserializable): {}", runId, e.getMessage());
        }
    }

    private static String humanize(String col) {
        return col != null ? col.replace('_', ' ') : "";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
