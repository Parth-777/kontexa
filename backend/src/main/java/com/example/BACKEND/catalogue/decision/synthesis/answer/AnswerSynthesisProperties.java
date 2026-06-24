package com.example.BACKEND.catalogue.decision.synthesis.answer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for post-warehouse answer synthesis.
 *
 * answer.synthesis.mode: legacy | gpt
 */
@ConfigurationProperties(prefix = "answer.synthesis")
public class AnswerSynthesisProperties {

    public enum Mode {
        legacy, gpt
    }

    private Mode mode = Mode.legacy;
    private int maxRowsInPrompt = 40;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.legacy;
    }

    public int getMaxRowsInPrompt() {
        return maxRowsInPrompt;
    }

    public void setMaxRowsInPrompt(int maxRowsInPrompt) {
        this.maxRowsInPrompt = maxRowsInPrompt > 0 ? maxRowsInPrompt : 40;
    }

    public boolean isLegacy() {
        return mode == Mode.legacy;
    }

    public boolean isGpt() {
        return mode == Mode.gpt;
    }
}
