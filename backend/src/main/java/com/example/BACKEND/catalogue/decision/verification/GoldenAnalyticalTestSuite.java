package com.example.BACKEND.catalogue.decision.verification;

import com.example.BACKEND.catalogue.decision.verification.GoldenAnalyticalBenchmark.ConcentrationPattern;
import com.example.BACKEND.catalogue.decision.verification.GoldenAnalyticalBenchmark.ValueRange;

import java.util.List;

/**
 * Mandatory NYC taxi analytical benchmarks — SQL + grouped results are source of truth.
 */
public final class GoldenAnalyticalTestSuite {

    private GoldenAnalyticalTestSuite() {}

    public static List<GoldenAnalyticalBenchmark> all() {
        return List.of(
                new GoldenAnalyticalBenchmark(
                        "G01",
                        "Revenue by trip distance",
                        "total_amount",
                        List.of("1-3", "3-5", "5-10", "0-1", "10-20", "20+"),
                        new ValueRange(25, 55),
                        new ValueRange(2.0, 15.0),
                        ConcentrationPattern.SHORT_DISTANCE_SKEW,
                        4
                ),
                new GoldenAnalyticalBenchmark(
                        "G02",
                        "Revenue by hour",
                        "total_amount",
                        List.of("18", "19", "17", "20"),
                        new ValueRange(20, 30),
                        new ValueRange(5.0, 10.0),
                        ConcentrationPattern.PEAK_HOUR,
                        6
                ),
                new GoldenAnalyticalBenchmark(
                        "G03",
                        "Tip contribution to revenue",
                        "tip_amount",
                        List.of("tips"),
                        new ValueRange(10, 20),
                        new ValueRange(1.0, 1.0),
                        ConcentrationPattern.TIP_COMPOSITION,
                        2
                ),
                new GoldenAnalyticalBenchmark(
                        "G04",
                        "Revenue by weekday",
                        "total_amount",
                        List.of("Weekday", "Weekend"),
                        new ValueRange(65, 75),
                        new ValueRange(2.0, 3.0),
                        ConcentrationPattern.WEEKDAY_SKEW,
                        2
                ),
                new GoldenAnalyticalBenchmark(
                        "G05",
                        "Top pickup zones by revenue",
                        "total_amount",
                        List.of("132", "161", "237"),
                        new ValueRange(28, 36),
                        new ValueRange(2.0, 4.0),
                        ConcentrationPattern.ZONE_RANKING,
                        5
                ),
                new GoldenAnalyticalBenchmark(
                        "G06",
                        "Average fare by distance",
                        "fare_amount",
                        List.of("20+", "10-20", "5-10", "3-5", "1-3", "0-1"),
                        new ValueRange(25, 35),
                        new ValueRange(5.0, 8.0),
                        ConcentrationPattern.FARE_DISTANCE_GRADIENT,
                        4
                )
        );
    }

    public static GoldenAnalyticalBenchmark findByQuestion(String question) {
        if (question == null) return null;
        String q = question.toLowerCase().trim();
        return all().stream()
                .filter(b -> q.contains(b.question().toLowerCase())
                        || b.question().toLowerCase().contains(q))
                .findFirst()
                .orElse(null);
    }
}
