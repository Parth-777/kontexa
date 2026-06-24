package com.example.BACKEND.catalogue.decision.synthesis.answer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Post-warehouse answer synthesis — legacy (no-op) or GPT narrative generation.
 */
@Service
public class AnswerSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(AnswerSynthesizer.class);

    private final AnswerSynthesisProperties properties;
    private final GptAnswerSynthesizer gptSynthesizer;

    public AnswerSynthesizer(
            AnswerSynthesisProperties properties,
            GptAnswerSynthesizer gptSynthesizer
    ) {
        this.properties = properties;
        this.gptSynthesizer = gptSynthesizer;
    }

    /**
     * @return synthesized narrative when mode=gpt and warehouse rows exist; empty otherwise
     */
    public Optional<AnswerSynthesisOutput> synthesize(AnswerSynthesisInput input) {
        if (properties.isLegacy()) {
            return Optional.empty();
        }
        if (input == null || !input.hasWarehouseRows()) {
            log.info("[answer-synthesis] skipped — no warehouse rows");
            return Optional.empty();
        }
        if (!input.execution().warehouseSucceeded()) {
            log.info("[answer-synthesis] skipped — warehouse did not succeed");
            return Optional.empty();
        }

        AnswerSynthesisOutput output = gptSynthesizer.synthesize(input);
        if (!output.hasContent()) {
            log.warn("[answer-synthesis] GPT returned empty synthesis");
            return Optional.empty();
        }
        log.info("[answer-synthesis] produced summary len={} findings={} type={}",
                output.executiveSummary().length(),
                output.keyFindings().size(),
                output.answerType());
        return Optional.of(output);
    }

    public String modeName() {
        return properties.getMode().name();
    }
}
