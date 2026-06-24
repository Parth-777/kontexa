package com.example.BACKEND.catalogue.decision.presentation.executive;

import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ComparativeFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.ContributionFinding.Segment;
import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.reasoning.GroundedAnalyticalFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanNarrativeFormatterTest {

    private HumanNarrativeFormatter formatter;
    private NarrativeCompressionLayer compression;

    @BeforeEach
    void setUp() {
        var aliases = new BusinessSemanticAliases();
        var metrics = new SemanticMetricFormatter();
        formatter = new HumanNarrativeFormatter(aliases, metrics);
        compression = new NarrativeCompressionLayer(formatter);
    }

    @Test
    void formatBucketLabel_distanceBuckets() {
        assertTrue(formatter.formatBucketLabel("1-3").contains("1–3 miles"));
        assertTrue(formatter.formatBucketLabel("20+").contains("20+ miles"));
    }

    @Test
    void contributionHeadline_usesConcentrationLanguage() {
        ContributionFinding finding = new ContributionFinding(
                "trip_distance",
                List.of(
                        new Segment("1-3", 1000, 40, 1, "DOMINANT"),
                        new Segment("20+", 100, 5, 2, "MINOR")
                ),
                "1-3", 40, 55, 0.4, 8.5, "", "total_amount"
        );
        var grounded = new GroundedAnalyticalFinding(finding, null, null, null, null, 1);
        String headline = formatter.headline(grounded, AnalyticalIntentType.CONTRIBUTION);
        assertTrue(headline.toLowerCase().contains("concentrated")
                || headline.toLowerCase().contains("revenue"));
        assertFalse(headline.toLowerCase().contains("outperform"));
    }

    @Test
    void comparativeSupport_usesMultipleNotPercent() {
        ComparativeFinding finding = new ComparativeFinding(
                "1-3", "20+", 850, 100, 750, 750, "A_LEADS", 8.5,
                "total_amount", ""
        );
        String support = formatter.enrichComparative(finding);
        assertTrue(support.contains("8.5×") || support.contains("×"));
        assertFalse(support.toLowerCase().contains("outperforms"));
    }

    @Test
    void compression_avoidsDuplicateHeadlineInSummary() {
        ContributionFinding finding = new ContributionFinding(
                "trip_distance",
                List.of(
                        new Segment("1-3", 1000, 40, 1, "DOMINANT"),
                        new Segment("20+", 100, 5, 2, "MINOR")
                ),
                "1-3", 40, 55, 0.4, 8.5, "", "total_amount"
        );
        var grounded = new GroundedAnalyticalFinding(finding, null, null, null, null, 1);
        var compressed = compression.compress(grounded, AnalyticalIntentType.CONTRIBUTION);
        assertFalse(compressed.executiveSummary().toLowerCase().contains(
                compressed.headline().toLowerCase()));
    }

    @Test
    void semanticMetricFormatter_currencyAndMultiple() {
        var metrics = new SemanticMetricFormatter();
        assertTrue(metrics.asCurrency(240_000_000).contains("$240M"));
        assertEquals("8.5×", metrics.percentDeltaAsMultiple(750));
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
