package com.example.BACKEND.catalogue.decision.calibration;

import java.util.List;

/**
 * Output of the {@link ResponseCalibrationEngine}.
 *
 * Governs how deeply the LLM should synthesise and which output
 * sections to produce. Passed to the prompt transformer so the
 * synthesis prompt is mode-aware before any LLM call is made.
 *
 * Field semantics:
 *
 *   mode                     — the selected response mode
 *   directAnswerFirst        — true: the LLM must answer the question in sentence 1
 *   includeStrategicImpls    — whether to populate strategicImplications
 *   includeOperationalRisks  — whether to populate operationalRisks
 *   includeBusinessCauses    — whether to populate businessCauses
 *   includeActions           — whether to populate actions
 *   maxNarrativeSentences    — soft cap on narrative length
 *   calibrationInstructions  — rendered verbatim into the synthesis prompt
 */
public record CalibrationResult(
        ResponseMode   mode,
        boolean        directAnswerFirst,
        boolean        includeStrategicImpls,
        boolean        includeOperationalRisks,
        boolean        includeBusinessCauses,
        boolean        includeActions,
        int            maxNarrativeSentences,
        List<String>   calibrationInstructions
) {

    /** Convenience factory for FACTUAL mode. */
    public static CalibrationResult factual() {
        return new CalibrationResult(
                ResponseMode.FACTUAL,
                true, false, false, false, false, 2,
                List.of(
                        "Answer the user's question directly in the FIRST sentence of the narrative.",
                        "State the key metric(s) and their comparative framing concisely.",
                        "Add at most 1 brief analytical observation if directly supported by evidence.",
                        "Return EMPTY arrays for: strategicImplications, operationalRisks, businessCauses, actions.",
                        "Do NOT produce executive escalation, strategic crisis framing, or dramatic urgency.",
                        "Tone: precise, calm, analytical — like reading a well-labelled dashboard."
                )
        );
    }

    /** Convenience factory for ANALYTICAL mode. */
    public static CalibrationResult analytical() {
        return new CalibrationResult(
                ResponseMode.ANALYTICAL,
                true, false, true, true, true, 4,
                List.of(
                        "Answer the user's question directly first, then provide ranked evidence.",
                        "Use comparative framing for each finding — numbers in context, not isolation.",
                        "Include 1-2 probable business causes framed as inferences, not facts.",
                        "Include 2-3 specific actions relevant to the analytical findings.",
                        "Return EMPTY arrays for: strategicImplications, operationalRisks.",
                        "Tone: evidence-heavy, commercially literate, moderate synthesis."
                )
        );
    }

    /** Convenience factory for INVESTIGATIVE mode. */
    public static CalibrationResult investigative() {
        return new CalibrationResult(
                ResponseMode.INVESTIGATIVE,
                false, true, true, true, true, 5,
                List.of(
                        "Lead with the highest-severity anomaly or finding from the evidence.",
                        "Reference investigation tree findings to explain WHICH segments drove the signal.",
                        "Frame unproven causes as hypotheses: 'the data may suggest', 'warrants investigation'.",
                        "Include strategic implications that emerge directly from the investigation.",
                        "Include operational risks that materialise if the anomaly persists unchecked.",
                        "Tone: analytical authority, urgency calibrated to evidence severity — not theatrical."
                )
        );
    }

    /** Convenience factory for EXECUTIVE_STRATEGIC mode. */
    public static CalibrationResult executiveStrategic() {
        return new CalibrationResult(
                ResponseMode.EXECUTIVE_STRATEGIC,
                false, true, true, true, true, 5,
                List.of(
                        "Produce a full executive briefing anchored to the highest-materiality observations.",
                        "Strategic implications must emerge from evidence — never from assumption.",
                        "Operational risks must name specific consequences, not generic warnings.",
                        "Actions must name an implicit owner and a specific intervention.",
                        "Prioritization rationale must reference the specific [OBS] that makes this most material.",
                        "Tone: McKinsey memo — definitive, commercially aware, no hedging on facts."
                )
        );
    }
}
