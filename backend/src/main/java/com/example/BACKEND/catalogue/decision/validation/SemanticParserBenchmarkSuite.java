package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.planning.AnalyticalIntentType;
import com.example.BACKEND.catalogue.decision.semantic.AnalyticalIntentPatterns.PatternKind;

import java.util.List;

/**
 * NYC taxi semantic parser benchmark questions.
 */
public final class SemanticParserBenchmarkSuite {

    private SemanticParserBenchmarkSuite() {}

    public static List<SemanticParserBenchmarkCase> all() {
        return List.of(
                new SemanticParserBenchmarkCase(
                        "SP01",
                        "How does tip amount contribute to total revenue?",
                        PatternKind.CONTRIBUTION,
                        AnalyticalIntentType.COMPOSITION,
                        "tip_amount",
                        "total_amount",
                        null,
                        true,
                        "Tip metric as numerator, revenue as denominator — not grouping"
                ),
                new SemanticParserBenchmarkCase(
                        "SP02",
                        "Revenue by trip distance",
                        PatternKind.DIMENSION_IMPACT,
                        AnalyticalIntentType.CONTRIBUTION,
                        "total_amount",
                        "trip_distance",
                        "trip_distance_bucket",
                        false,
                        "Trip distance as dimension bucket, revenue aggregated"
                ),
                new SemanticParserBenchmarkCase(
                        "SP03",
                        "Which trip distances generate most revenue?",
                        PatternKind.DIMENSION_IMPACT,
                        AnalyticalIntentType.CONTRIBUTION,
                        "total_amount",
                        "trip_distance",
                        "trip_distance_bucket",
                        false,
                        "Ranking-style phrasing with distance dimension"
                ),
                new SemanticParserBenchmarkCase(
                        "SP04",
                        "What share of revenue comes from tips?",
                        PatternKind.CONTRIBUTION,
                        AnalyticalIntentType.COMPOSITION,
                        "tip_amount",
                        "total_amount",
                        null,
                        true,
                        "Share-of composition ratio"
                ),
                new SemanticParserBenchmarkCase(
                        "SP05",
                        "Which pickup zones drive highest revenue?",
                        PatternKind.DIMENSION_IMPACT,
                        AnalyticalIntentType.CONTRIBUTION,
                        "total_amount",
                        "pickup_zone",
                        "pickup_zone_bucket",
                        false,
                        "Zone dimension impact on revenue"
                ),
                new SemanticParserBenchmarkCase(
                        "SP06",
                        "How does trip distance affect revenue?",
                        PatternKind.DIMENSION_IMPACT,
                        AnalyticalIntentType.CONTRIBUTION,
                        "total_amount",
                        "trip_distance",
                        "trip_distance_bucket",
                        false,
                        "Affect/impact phrasing with distance dimension"
                )
        );
    }
}
