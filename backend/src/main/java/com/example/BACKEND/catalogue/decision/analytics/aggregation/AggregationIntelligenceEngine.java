package com.example.BACKEND.catalogue.decision.analytics.aggregation;

import com.example.BACKEND.catalogue.decision.analytics.RowAnalytics;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.ConstructedEntity;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.RankedEntity;
import com.example.BACKEND.catalogue.decision.execution.ExecutionFindings.StructuralFinding;
import com.example.BACKEND.catalogue.decision.execution.framework.ColumnProfile;
import com.example.BACKEND.catalogue.decision.execution.framework.ComputationBlueprint;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfile;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generic grouped-aggregation intelligence engine.
 *
 * Goal:
 *   dataset -> semantic dimensions -> grouped aggregations -> ranking/comparisons
 *   -> structural findings
 *
 * Design constraints:
 *   - dataset-agnostic (no domain hardcoding)
 *   - schema-driven grouping opportunity detection
 *   - computes comparative/ranked evidence before synthesis
 */
@Component
public class AggregationIntelligenceEngine {

    private static final int MAX_GROUPS_PER_SPEC = 40;
    private static final int MAX_CANDIDATE_SPECS = 8;
    private static final int MAX_RANKED = 20;

    public AggregationIntelligenceResult analyze(
            List<Map<String, Object>> rows,
            SchemaProfile profile,
            ComputationBlueprint blueprint
    ) {
        if (rows == null || rows.isEmpty() || profile == null || !profile.hasValues()) {
            return AggregationIntelligenceResult.empty();
        }

        ColumnProfile primaryValue = profile.primaryValue();
        if (primaryValue == null) return AggregationIntelligenceResult.empty();
        String primaryMetricKey = primaryValue.columnName();

        List<GroupingSpec> specs = buildGroupingSpecs(profile);
        if (specs.isEmpty()) return AggregationIntelligenceResult.empty();

        List<GroupedCandidate> candidates = specs.stream()
                .map(spec -> aggregateBySpec(rows, spec, profile, primaryMetricKey))
                .filter(c -> c != null && c.entities().size() >= 3)
                .sorted(Comparator.comparingDouble(GroupedCandidate::signalScore).reversed())
                .limit(MAX_CANDIDATE_SPECS)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return AggregationIntelligenceResult.empty();

        GroupedCandidate best = candidates.get(0);
        List<RankedEntity> contributorRanking = rank(best.entities(), primaryMetricKey);
        List<RankedEntity> efficiencyRanking = rankEfficiency(best.entities());
        List<StructuralFinding> findings = generateFindings(candidates, contributorRanking, efficiencyRanking, primaryMetricKey);

        return new AggregationIntelligenceResult(best.entities(), contributorRanking, efficiencyRanking, findings);
    }

    private List<GroupingSpec> buildGroupingSpecs(SchemaProfile profile) {
        List<GroupingSpec> specs = new ArrayList<>();

        // 1) Every discovered dimension is a grouping opportunity
        profile.dimensions().stream()
                .sorted(Comparator.comparingInt(ColumnProfile::cardinality))
                .limit(4)
                .forEach(dim -> specs.add(new GroupingSpec(
                        dim.columnName(),
                        false,
                        row -> normalize(row.get(dim.columnName()))
                )));

        // 2) Composite grouping from top 2 dimensions (route/corridor/account->segment style)
        List<ColumnProfile> dims = profile.dimensions().stream()
                .sorted(Comparator.comparingInt(ColumnProfile::cardinality))
                .limit(2)
                .collect(Collectors.toList());
        if (dims.size() == 2) {
            specs.add(new GroupingSpec(
                    dims.get(0).columnName() + "→" + dims.get(1).columnName(),
                    false,
                    row -> {
                        String a = normalize(row.get(dims.get(0).columnName()));
                        String b = normalize(row.get(dims.get(1).columnName()));
                        if (a == null || b == null) return null;
                        return a + " → " + b;
                    }
            ));
        }

        // 3) Temporal buckets from any time-like column
        for (ColumnProfile tc : profile.timeColumns()) {
            String col = tc.columnName();
            specs.add(new GroupingSpec(col + ":hour_of_day", true, row -> deriveHourBucket(row.get(col))));
            specs.add(new GroupingSpec(col + ":weekday", true, row -> deriveWeekdayBucket(row.get(col))));
            specs.add(new GroupingSpec(col + ":month", true, row -> deriveMonthBucket(row.get(col))));
        }

        return specs;
    }

    private GroupedCandidate aggregateBySpec(
            List<Map<String, Object>> rows,
            GroupingSpec spec,
            SchemaProfile profile,
            String primaryMetricKey
    ) {
        Map<String, AggregationAccumulator> grouped = new LinkedHashMap<>();
        ColumnProfile vol = profile.primaryVolume();
        String volumeKey = vol != null ? vol.columnName() : null;

        for (Map<String, Object> row : rows) {
            String key = spec.keyExtractor().apply(row);
            if (key == null || key.isBlank()) continue;

            double primaryValue = RowAnalytics.toDouble(row.get(primaryMetricKey));
            if (Double.isNaN(primaryValue)) continue;

            double volume = Double.NaN;
            if (volumeKey != null) {
                volume = RowAnalytics.toDouble(row.get(volumeKey));
            }
            if (Double.isNaN(volume)) volume = 1.0;

            grouped.computeIfAbsent(key, k -> new AggregationAccumulator())
                    .accumulate(primaryValue, volume);
        }

        if (grouped.size() < 3) return null;

        List<ConstructedEntity> entities = grouped.entrySet().stream()
                .map(e -> toEntity(e.getKey(), spec.name(), e.getValue(), primaryMetricKey))
                .sorted(Comparator.comparingDouble((ConstructedEntity ce) ->
                        ce.metrics().getOrDefault(primaryMetricKey, 0.0)).reversed())
                .limit(MAX_GROUPS_PER_SPEC)
                .collect(Collectors.toList());

        if (entities.isEmpty()) return null;

        List<Double> primaryValues = entities.stream()
                .map(e -> e.metrics().getOrDefault(primaryMetricKey, 0.0))
                .collect(Collectors.toList());

        double dispersion = RowAnalytics.stdDev(primaryValues);
        double concentration = RowAnalytics.topNSharePercent(primaryValues.stream().sorted().collect(Collectors.toList()), 0.10);
        double signalScore = dispersion + (concentration / 10.0) + entities.size() * 0.05;

        return new GroupedCandidate(spec.name(), spec.temporal(), entities, signalScore);
    }

    private ConstructedEntity toEntity(String key, String type, AggregationAccumulator acc, String primaryMetricKey) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(primaryMetricKey, acc.primarySum);
        metrics.put("volume_total", acc.volumeSum);
        metrics.put("efficiency_ratio", acc.volumeSum > 0 ? acc.primarySum / acc.volumeSum : 0);
        metrics.put("_sample_count", (double) acc.sampleCount);
        return new ConstructedEntity(key, type, metrics, acc.sampleCount);
    }

    private List<RankedEntity> rank(List<ConstructedEntity> entities, String metricKey) {
        List<ConstructedEntity> valid = entities.stream()
                .filter(e -> {
                    Double v = e.metrics().get(metricKey);
                    return v != null && !Double.isNaN(v);
                })
                .sorted(Comparator.comparingDouble((ConstructedEntity e) -> e.metrics().get(metricKey)).reversed())
                .limit(MAX_RANKED)
                .collect(Collectors.toList());
        if (valid.isEmpty()) return List.of();

        List<Double> values = valid.stream().map(e -> e.metrics().get(metricKey)).collect(Collectors.toList());
        double peerAvg = RowAnalytics.mean(values);

        List<RankedEntity> ranked = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            double val = valid.get(i).metrics().get(metricKey);
            double pct = RowAnalytics.percentileRank(values, val);
            ranked.add(new RankedEntity(
                    i + 1,
                    valid.get(i).entityKey(),
                    metricKey,
                    val,
                    peerAvg,
                    peerAvg > 0 ? val / peerAvg : 1.0,
                    pct,
                    tier(pct)
            ));
        }
        return ranked;
    }

    private List<RankedEntity> rankEfficiency(List<ConstructedEntity> entities) {
        boolean hasEff = entities.stream().anyMatch(e -> e.metrics().containsKey("efficiency_ratio"));
        return hasEff ? rank(entities, "efficiency_ratio") : List.of();
    }

    private List<StructuralFinding> generateFindings(
            List<GroupedCandidate> candidates,
            List<RankedEntity> contributorRanking,
            List<RankedEntity> efficiencyRanking,
            String metricKey
    ) {
        List<StructuralFinding> out = new ArrayList<>();
        GroupedCandidate best = candidates.get(0);

        if (!contributorRanking.isEmpty()) {
            RankedEntity top = contributorRanking.get(0);
            out.add(new StructuralFinding(
                    String.format("Top contributor [%s] in grouping [%s] delivers %.2f %s (%.1fx peer average).",
                            top.entityKey(), best.groupingName(), top.value(), metricKey.replace("_", " "),
                            top.multiplierVsAverage()),
                    top.multiplierVsAverage(),
                    "grouped contributor ranking",
                    top.multiplierVsAverage() >= 1.8
            ));

            int topN = Math.max(1, (int) Math.ceil(contributorRanking.size() * 0.10));
            topN = Math.min(topN, contributorRanking.size());
            double topNSum = contributorRanking.subList(0, topN).stream().mapToDouble(RankedEntity::value).sum();
            double total = contributorRanking.stream().mapToDouble(RankedEntity::value).sum();
            if (total > 0) {
                double share = 100.0 * topNSum / total;
                out.add(new StructuralFinding(
                        String.format("Top %.0f%% contributors account for %.1f%% of grouped %s in [%s] (Pareto concentration).",
                                100.0 * topN / contributorRanking.size(), share, metricKey.replace("_", " "), best.groupingName()),
                        share,
                        "pareto concentration analysis",
                        share >= 50
                ));
            }
        }

        if (!efficiencyRanking.isEmpty()) {
            RankedEntity peak = efficiencyRanking.get(0);
            out.add(new StructuralFinding(
                    String.format("Peak efficiency bucket [%s] achieves %.3f value-per-unit in grouping [%s] (%.1fx peer average).",
                            peak.entityKey(), peak.value(), best.groupingName(), peak.multiplierVsAverage()),
                    peak.multiplierVsAverage(),
                    "efficiency ranking by grouped aggregation",
                    peak.multiplierVsAverage() >= 1.7
            ));
        }

        // Temporal concentration from best temporal candidate, if present
        candidates.stream().filter(GroupedCandidate::temporal).findFirst().ifPresent(tc -> {
            List<RankedEntity> temporalRank = rank(tc.entities(), metricKey);
            if (!temporalRank.isEmpty()) {
                RankedEntity peak = temporalRank.get(0);
                out.add(new StructuralFinding(
                        String.format("Temporal peak [%s] in [%s] has highest grouped %s at %.2f (%.1fx peer average).",
                                peak.entityKey(), tc.groupingName(), metricKey.replace("_", " "), peak.value(), peak.multiplierVsAverage()),
                        peak.multiplierVsAverage(),
                        "temporal grouped ranking",
                        peak.multiplierVsAverage() >= 1.5
                ));
            }
        });

        return out.stream().limit(6).collect(Collectors.toList());
    }

    private String tier(double pct) {
        if (pct >= 90) return "TOP_DECILE";
        if (pct >= 75) return "TOP_QUARTILE";
        if (pct >= 55) return "ABOVE_AVERAGE";
        if (pct >= 35) return "AVERAGE";
        if (pct >= 15) return "BELOW_AVERAGE";
        return "BOTTOM_QUARTILE";
    }

    private String normalize(Object value) {
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isBlank() ? null : s;
    }

    private String deriveHourBucket(Object raw) {
        LocalDateTime dt = parseDateTime(raw);
        return dt == null ? null : String.format("%02d:00-%02d:00", dt.getHour(), (dt.getHour() + 1) % 24);
    }

    private String deriveWeekdayBucket(Object raw) {
        LocalDateTime dt = parseDateTime(raw);
        return dt == null ? null : dt.getDayOfWeek().name();
    }

    private String deriveMonthBucket(Object raw) {
        LocalDateTime dt = parseDateTime(raw);
        return dt == null ? null : dt.getMonth().name();
    }

    private LocalDateTime parseDateTime(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof OffsetDateTime odt) return odt.toLocalDateTime();
        if (raw instanceof ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (raw instanceof Instant inst) return LocalDateTime.ofInstant(inst, ZoneOffset.UTC);
        if (raw instanceof Date date) return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        if (raw instanceof Number n) {
            long v = n.longValue();
            // heuristic: milliseconds if too large for epoch seconds
            if (Math.abs(v) > 100_000_000_000L) {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZoneOffset.UTC);
            }
            if (Math.abs(v) > 1_000_000_000L) {
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(v), ZoneOffset.UTC);
            }
            return null;
        }
        String s = raw.toString().trim();
        if (s.isBlank()) return null;
        try { return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime(); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_DATE).atStartOfDay(); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC); } catch (DateTimeParseException ignored) {}
        return null;
    }

    private record GroupingSpec(
            String name,
            boolean temporal,
            Function<Map<String, Object>, String> keyExtractor
    ) {}

    private record GroupedCandidate(
            String groupingName,
            boolean temporal,
            List<ConstructedEntity> entities,
            double signalScore
    ) {}

    private static final class AggregationAccumulator {
        double primarySum = 0;
        double volumeSum = 0;
        long sampleCount = 0;

        void accumulate(double primary, double volume) {
            this.primarySum += primary;
            this.volumeSum += volume;
            this.sampleCount++;
        }
    }
}

