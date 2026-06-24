package com.example.BACKEND.catalogue.decision.presentation;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.*;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.presentation.executive.HumanNarrativeFormatter;
import com.example.BACKEND.catalogue.decision.presentation.executive.NarrativeCompressionLayer;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import com.example.BACKEND.catalogue.decision.reasoning.StatisticalInterpretation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Intent-specific factual insight templates — descriptive only, no executive filler.
 */
@Component
public class InsightTemplateEngine {

    private final FactualLanguageGuard languageGuard;
    private final HumanNarrativeFormatter human;
    private final NarrativeCompressionLayer compression;

    public InsightTemplateEngine(
            FactualLanguageGuard languageGuard,
            HumanNarrativeFormatter human,
            NarrativeCompressionLayer compression
    ) {
        this.languageGuard = languageGuard;
        this.human = human;
        this.compression = compression;
    }

    public String render(
            AnalyticalFinding finding,
            StatisticalInterpretation stats,
            AnalyticalIntentType intent,
            double confidence
    ) {
        var grounded = new GroundedAnalyticalFinding(finding, stats, null, null, null, 0);
        var compressed = compression.compress(grounded, intent);
        String text = compressed.executiveSummary().isBlank()
                ? compressed.keyTakeaway()
                : compressed.keyTakeaway() + " " + compressed.executiveSummary();
        return languageGuard.sanitizeSentence(text, false, confidence);
    }

    /** Chart-caption style — single evidence line, no duplication with headline. */
    public String chartCaption(
            AnalyticalFinding finding,
            StatisticalInterpretation stats,
            AnalyticalIntentType intent
    ) {
        var grounded = new GroundedAnalyticalFinding(finding, stats, null, null, null, 0);
        String evidence = compression.compress(grounded, intent).evidenceSentence();
        return languageGuard.sanitize(evidence);
    }
}
