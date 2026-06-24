package com.example.BACKEND.catalogue.decision.reasoning;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.presentation.FactualLanguageGuard;
import com.example.BACKEND.catalogue.decision.presentation.InsightTemplateEngine;
import org.springframework.stereotype.Component;

/**
 * Produces minimal factual narratives from intent templates — no executive prose or next steps.
 */
@Component
public class NarrativeIntelligenceEngine {

    private final InsightTemplateEngine templates;
    private final FactualLanguageGuard languageGuard;

    public NarrativeIntelligenceEngine(InsightTemplateEngine templates, FactualLanguageGuard languageGuard) {
        this.templates = templates;
        this.languageGuard = languageGuard;
    }

    public String synthesize(
            AnalyticalFinding finding,
            StatisticalInterpretation stats,
            String comparativeNarrative,
            AnalyticalIntentType intent,
            double confidence
    ) {
        String factual = templates.render(finding, stats, intent, confidence);
        if (!factual.isBlank()) {
            return languageGuard.sanitize(factual);
        }
        if (comparativeNarrative != null && !comparativeNarrative.isBlank()) {
            return languageGuard.sanitize(comparativeNarrative);
        }
        return "";
    }

    public String chartExplanation(
            AnalyticalFinding finding,
            StatisticalInterpretation stats,
            AnalyticalIntentType intent
    ) {
        return languageGuard.sanitize(templates.chartCaption(finding, stats, intent));
    }
}
