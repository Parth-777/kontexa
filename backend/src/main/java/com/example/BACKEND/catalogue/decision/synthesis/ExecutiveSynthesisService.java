package com.example.BACKEND.catalogue.decision.synthesis;

import com.example.BACKEND.catalogue.decision.analytics.AnalyticalDepthResult;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings;
import com.example.BACKEND.catalogue.decision.findings.StructuredFindingsBundle;
import com.example.BACKEND.catalogue.decision.calibration.CalibrationResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.planning.EvidenceCoverageReport;
import com.example.BACKEND.catalogue.decision.planning.InvestigationPlan;
import com.example.BACKEND.catalogue.decision.playbooks.Playbook;
import com.example.BACKEND.catalogue.decision.presentation.NarrativeCompressor;
import com.example.BACKEND.catalogue.llm.OpenAiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transforms ranked evidence into an executive {@link InsightOutput} via LLM synthesis.
 *
 * LLM contract (enforced by {@link EvidenceToPromptTransformer}):
 *   ONLY receives: ranked evidence, comparative contexts, investigation tree findings.
 *   NEVER receives: raw SQL rows, query text, table dumps.
 *
 * Output contract:
 *   title, narrative, actions, strategicImplications, operationalRisks,
 *   businessCauses, prioritizationRationale.
 */
@Service
public class ExecutiveSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(ExecutiveSynthesisService.class);

    private final OpenAiClient              openAiClient;
    private final EvidenceToPromptTransformer promptTransformer;
    private final ObjectMapper              objectMapper;
    private final NarrativeCompressor       narrativeCompressor;

    public ExecutiveSynthesisService(
            OpenAiClient              openAiClient,
            EvidenceToPromptTransformer promptTransformer,
            ObjectMapper              objectMapper,
            NarrativeCompressor       narrativeCompressor
    ) {
        this.openAiClient      = openAiClient;
        this.promptTransformer = promptTransformer;
        this.objectMapper      = objectMapper;
        this.narrativeCompressor = narrativeCompressor;
    }

    public InsightOutput synthesise(
            List<RankedEvidence> ranked,
            List<EvidenceObject> rawEvidence,
            IntentResolution     intent
    ) {
        return synthesise(ranked, rawEvidence, intent,
                null, null, null, null, null, null, null, null);
    }

    public InsightOutput synthesise(
            List<RankedEvidence>   ranked,
            List<EvidenceObject>   rawEvidence,
            IntentResolution       intent,
            Playbook               playbook,
            ConstitutionReview     constitution,
            CalibrationResult      calibration,
            InvestigationPlan      investigationPlan,
            EvidenceCoverageReport coverageReport,
            AnalyticalDepthResult  depthResult,
            ExecutionFindings      executionFindings
    ) {
        return synthesise(ranked, rawEvidence, intent, playbook, constitution, calibration,
                investigationPlan, coverageReport, depthResult, executionFindings, null);
    }

    public InsightOutput synthesise(
            List<RankedEvidence>    ranked,
            List<EvidenceObject>    rawEvidence,
            IntentResolution        intent,
            Playbook                playbook,
            ConstitutionReview      constitution,
            CalibrationResult       calibration,
            InvestigationPlan       investigationPlan,
            EvidenceCoverageReport  coverageReport,
            AnalyticalDepthResult   depthResult,
            ExecutionFindings       executionFindings,
            StructuredFindingsBundle findingsBundle
    ) {
        if (ranked.isEmpty()) return emptyInsight(intent);

        String basePrompt   = SynthesisTemplates.systemPrompt();
        String extension    = (playbook != null) ? playbook.synthesisSystemExtension() : "";
        String systemPrompt = extension.isBlank() ? basePrompt : basePrompt + "\n" + extension;
        String userPrompt   = promptTransformer.transform(ranked, rawEvidence, intent, playbook,
                constitution, calibration, investigationPlan, coverageReport,
                depthResult, executionFindings, findingsBundle);

        log.info("[synthesis] LLM call | runId={} evidenceItems={} playbook={} observations={} filteredSpeculation={} policyVersion={}",
                intent.runId(), ranked.size(),
                playbook != null ? playbook.playbookKey() : "NONE",
                constitution != null ? constitution.observations().size() : 0,
                constitution != null ? constitution.filteredSpeculation().size() : 0,
                ranked.get(0).policyVersion());

        String llmResponse = openAiClient.chat(systemPrompt, userPrompt);
        log.debug("[synthesis] LLM response received");

        return parseInsight(llmResponse, intent, ranked);
    }

    // ─── private ───────────────────────────────────────────────────────

    private InsightOutput parseInsight(String json, IntentResolution intent, List<RankedEvidence> ranked) {
        List<String> evRefs = ranked.stream().map(RankedEvidence::evidenceId).toList();
        try {
            JsonNode node = objectMapper.readTree(json);

            String   title     = narrativeCompressor.clean(node.path("title").asText("Analysis Result"));
            String   narrative = narrativeCompressor.compress(
                    node.path("executive_summary").asText("")
                            + " " + node.path("narrative").asText(""),
                    3);
            List<String> actions            = parseList(node.path("actions"));
            List<String> strategicImpl      = parseList(node.path("strategicImplications"));
            List<String> operationalRisks   = parseList(node.path("operationalRisks"));
            List<String> businessCauses     = parseList(node.path("businessCauses"));
            String prioritizationRationale  = node.path("prioritizationRationale").asText("");

            return new InsightOutput(
                    "ins-" + UUID.randomUUID().toString().substring(0, 8),
                    title, narrative, actions, evRefs,
                    strategicImpl, operationalRisks, businessCauses, prioritizationRationale
            );
        } catch (Exception ex) {
            log.warn("[synthesis] LLM JSON parse failed, using raw text: {}", ex.getMessage());
            return new InsightOutput(
                    "ins-" + UUID.randomUUID().toString().substring(0, 8),
                    "Analysis for: " + intent.question(),
                    json,
                    List.of(), evRefs,
                    List.of(), List.of(), List.of(), ""
            );
        }
    }

    private List<String> parseList(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        node.forEach(n -> { if (n.isTextual()) list.add(n.asText()); });
        return list;
    }

    private InsightOutput emptyInsight(IntentResolution intent) {
        return new InsightOutput(
                "ins-" + UUID.randomUUID().toString().substring(0, 8),
                "Insufficient evidence to generate insight",
                "The analytical pipeline did not find enough evidence to produce a meaningful insight for: \""
                        + intent.question() + "\". "
                        + "Ensure the warehouse connection is active and the catalogue contains approved, data-bearing tables.",
                List.of(
                        "Verify BigQuery connection is active and credentials are valid",
                        "Confirm catalogue has been approved with at least one table",
                        "Re-run after data refresh"
                ),
                List.of(),
                List.of("No data foundation — analytical substrate is incomplete"),
                List.of("Decision latency risk: without data, leadership is operating blind"),
                List.of("Warehouse connection may be inactive or catalogue may not be approved"),
                "No evidence was generated — all materiality scores are zero. Resolve data access first."
        );
    }
}
