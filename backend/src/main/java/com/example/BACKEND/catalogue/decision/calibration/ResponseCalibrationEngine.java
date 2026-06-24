package com.example.BACKEND.catalogue.decision.calibration;

import com.example.BACKEND.catalogue.decision.calibration.QueryComplexityAnalyser.ComplexitySignals;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Response Calibration Engine.
 *
 * Operates AFTER {@link com.example.BACKEND.catalogue.decision.reasoning.ReasoningConstitutionEngine}
 * and BEFORE {@link com.example.BACKEND.catalogue.decision.synthesis.ExecutiveSynthesisService}.
 *
 * Determines HOW MUCH intelligence the system should express by selecting
 * the appropriate {@link ResponseMode} and producing a {@link CalibrationResult}
 * that controls synthesis depth.
 *
 * The four calibrated modes:
 *
 *   FACTUAL            → Direct answer + 1-2 observations. No escalation.
 *   ANALYTICAL         → Evidence-heavy, comparative, moderate synthesis.
 *   INVESTIGATIVE      → Deep drilldown, labelled hypotheses, causal investigation.
 *   EXECUTIVE_STRATEGIC → Full briefing — implications, risks, actions, materiality.
 *
 * Key principle: synthesis depth MUST scale with question depth.
 * Not every query deserves executive escalation.
 */
@Service
public class ResponseCalibrationEngine {

    private static final Logger log = LoggerFactory.getLogger(ResponseCalibrationEngine.class);

    private final QueryComplexityAnalyser complexityAnalyser;

    public ResponseCalibrationEngine(QueryComplexityAnalyser complexityAnalyser) {
        this.complexityAnalyser = complexityAnalyser;
    }

    /**
     * Calibrate response depth for the current execution.
     *
     * @param intent        resolved analytical intent (contains the original question)
     * @param ranked        materiality-ranked evidence (contains feature signals)
     * @param evidence      full evidence objects (for investigation tree depth)
     * @param constitution  epistemic review (for anomaly and filtering signals)
     * @return              a {@link CalibrationResult} governing LLM synthesis behaviour
     */
    public CalibrationResult calibrate(
            IntentResolution     intent,
            List<RankedEvidence> ranked,
            List<EvidenceObject> evidence,
            ConstitutionReview   constitution
    ) {
        ComplexitySignals signals = complexityAnalyser.analyse(intent, ranked);

        int investigationDepth = countInvestigationNodes(evidence);
        int filteredSpeculation = constitution != null ? constitution.filteredSpeculation().size() : 0;
        int observations       = constitution != null ? constitution.observations().size() : 0;

        ResponseMode mode = selectMode(signals, investigationDepth, filteredSpeculation, observations);

        CalibrationResult result = buildResult(mode);

        log.info("[calibration] runId={} mode={} factual={} analytical={} investigative={} strategic={} " +
                        "topMateriality={} depth={} anomalies={} filteredSpeculation={}",
                intent.runId(), mode,
                signals.isLikelyFactual(), signals.isLikelyAnalytical(),
                signals.isLikelyInvestigative(), signals.isLikelyStrategic(),
                String.format("%.2f", signals.topMaterialityScore()), investigationDepth,
                signals.anomalyCount(), filteredSpeculation);

        return result;
    }

    // ─── mode selection logic ────────────────────────────────────────────

    /**
     * Priority order (highest wins):
     *   EXECUTIVE_STRATEGIC  — explicit strategic intent
     *   INVESTIGATIVE        — anomaly/root-cause/deterioration + high-materiality evidence
     *   ANALYTICAL           — ranking/comparison/trend
     *   FACTUAL              — contribution/percentage/count/KPI lookup
     *
     * Fallback: if nothing matches cleanly → ANALYTICAL
     *
     * Evidence signals can upgrade a mode:
     *   - filteredSpeculation > 0  → upgraded to INVESTIGATIVE (something complex was blocked)
     *   - investigationDepth ≥ 3   → upgraded to INVESTIGATIVE
     *   - topMaterialityScore > 0.8 → upgraded to EXECUTIVE_STRATEGIC
     */
    private ResponseMode selectMode(
            ComplexitySignals signals,
            int               investigationDepth,
            int               filteredSpeculation,
            int               observations
    ) {
        // Tier 4: Executive strategic — highest escalation
        if (signals.isLikelyStrategic()) return ResponseMode.EXECUTIVE_STRATEGIC;
        if (signals.topMaterialityScore() > 0.80 && investigationDepth >= 3)
            return ResponseMode.EXECUTIVE_STRATEGIC;

        // Tier 3: Investigative
        if (signals.isLikelyInvestigative()) return ResponseMode.INVESTIGATIVE;
        if (filteredSpeculation > 0 && signals.anomalyCount() > 0)
            return ResponseMode.INVESTIGATIVE;
        if (investigationDepth >= 3 && signals.topMaterialityScore() > 0.50)
            return ResponseMode.INVESTIGATIVE;

        // Tier 2: Analytical
        if (signals.isLikelyAnalytical()) return ResponseMode.ANALYTICAL;

        // Tier 1: Factual
        if (signals.isLikelyFactual()) return ResponseMode.FACTUAL;

        // Fallback: if evidence has enough observations, do at least ANALYTICAL
        if (observations >= 3) return ResponseMode.ANALYTICAL;

        // Default
        return ResponseMode.FACTUAL;
    }

    private CalibrationResult buildResult(ResponseMode mode) {
        return switch (mode) {
            case FACTUAL             -> CalibrationResult.factual();
            case ANALYTICAL          -> CalibrationResult.analytical();
            case INVESTIGATIVE       -> CalibrationResult.investigative();
            case EXECUTIVE_STRATEGIC -> CalibrationResult.executiveStrategic();
        };
    }

    private int countInvestigationNodes(List<EvidenceObject> evidence) {
        return evidence.stream()
                .mapToInt(ev -> ev.investigationTree() == null ? 0 : ev.investigationTree().size())
                .sum();
    }
}
