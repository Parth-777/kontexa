package com.example.BACKEND.catalogue.decision.execution.materialization;

/**
 * Human-readable interpretation of a Pearson correlation coefficient.
 */
public final class CorrelationInterpretation {

    private CorrelationInterpretation() {}

    public record InterpretedCorrelation(
            String strength,
            String direction,
            String summary
    ) {}

    public static InterpretedCorrelation interpret(double coefficient) {
        double abs = Math.abs(coefficient);
        String direction = coefficient < 0 ? "negative" : coefficient > 0 ? "positive" : "no";
        String strength;
        if (abs < 0.1) strength = "negligible";
        else if (abs < 0.3) strength = "weak";
        else if (abs < 0.5) strength = "moderate";
        else if (abs < 0.7) strength = "strong";
        else strength = "very strong";

        String summary;
        if (abs < 0.1) {
            summary = String.format(
                    "There is essentially no linear relationship (r=%.4f) — changes in one variable "
                            + "do not meaningfully track changes in the other.",
                    coefficient);
        } else {
            summary = String.format(
                    "A %s %s linear relationship was detected (r=%.4f). "
                            + "As one variable increases, the other tends to %s.",
                    strength, direction, coefficient,
                    coefficient < 0 ? "decrease" : "increase");
        }
        return new InterpretedCorrelation(strength, direction, summary);
    }
}
