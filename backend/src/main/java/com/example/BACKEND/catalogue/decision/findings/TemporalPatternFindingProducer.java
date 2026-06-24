package com.example.BACKEND.catalogue.decision.findings;

import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedGroupEntry;
import com.example.BACKEND.catalogue.decision.execution.materialization.MaterializedQueryResult.MaterializedGrouping;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.TemporalPatternFinding;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.TemporalPatternFinding.InflectionPoint;
import com.example.BACKEND.catalogue.decision.findings.AnalyticalFinding.TemporalPatternFinding.TemporalPeriod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Produces {@link TemporalPatternFinding}s from a temporal {@link MaterializedGrouping}
 * (i.e. one whose spec is {@code SpecType.DERIVED_TIME}).
 *
 * Computes:
 *   - Chronologically ordered periods (by entityKey label when parseable, by rank otherwise)
 *   - Peak and trough identification
 *   - Volatility (coefficient of variation)
 *   - Momentum: compares first-half average to second-half average
 *   - Inflection points: consecutive periods where |changePct| > threshold
 */
@Component
public class TemporalPatternFindingProducer {

    private static final double INFLECTION_THRESHOLD = 25.0; // % change to qualify as inflection

    public Optional<TemporalPatternFinding> produce(MaterializedGrouping grouping) {
        if (grouping == null || !grouping.hasData()) return Optional.empty();
        if (!grouping.spec().isTemporal()) return Optional.empty();

        List<MaterializedGroupEntry> entries = new ArrayList<>(grouping.rankedEntries());
        // Sort chronologically by entityKey (numeric sort for hours/months)
        entries.sort((a, b) -> compareTemporalKeys(a.entityKey(), b.entityKey()));

        if (entries.size() < 3) return Optional.empty();

        double mean = entries.stream().mapToDouble(MaterializedGroupEntry::totalValue)
                .average().orElse(0);
        double variance = entries.stream()
                .mapToDouble(e -> Math.pow(e.totalValue() - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double volatility = mean > 0 ? stdDev / mean : 0;

        MaterializedGroupEntry peak   = entries.stream()
                .max((a, b) -> Double.compare(a.totalValue(), b.totalValue())).orElse(entries.get(0));
        MaterializedGroupEntry trough = entries.stream()
                .min((a, b) -> Double.compare(a.totalValue(), b.totalValue())).orElse(entries.get(0));

        List<TemporalPeriod> periods = entries.stream()
                .map(e -> new TemporalPeriod(
                        e.entityKey(), e.totalValue(), e.rank(),
                        e.entityKey().equals(peak.entityKey()),
                        e.entityKey().equals(trough.entityKey())
                ))
                .collect(Collectors.toList());

        List<InflectionPoint> inflections = detectInflections(entries);
        String momentum  = deriveMomentum(entries);

        String summary = buildSummary(grouping.spec().displayLabel(),
                peak, trough, volatility, momentum, entries.size(), mean);

        return Optional.of(new TemporalPatternFinding(
                grouping.spec().displayLabel(),
                periods, peak.entityKey(), peak.totalValue(),
                trough.entityKey(), trough.totalValue(),
                volatility, momentum, inflections, summary
        ));
    }

    // ─── Inflection detection ─────────────────────────────────────────────

    private List<InflectionPoint> detectInflections(List<MaterializedGroupEntry> entries) {
        List<InflectionPoint> inflections = new ArrayList<>();
        for (int i = 1; i < entries.size(); i++) {
            double prev = entries.get(i - 1).totalValue();
            double curr = entries.get(i).totalValue();
            if (prev <= 0) continue;
            double changePct = (curr - prev) / prev * 100;
            if (Math.abs(changePct) >= INFLECTION_THRESHOLD) {
                String dir = changePct > 0 ? "SPIKE" : "DROP";
                inflections.add(new InflectionPoint(
                        entries.get(i - 1).entityKey(),
                        entries.get(i).entityKey(),
                        curr - prev,
                        changePct,
                        dir
                ));
            }
        }
        return inflections;
    }

    // ─── Momentum ─────────────────────────────────────────────────────────

    private String deriveMomentum(List<MaterializedGroupEntry> entries) {
        if (entries.size() < 4) return "STABLE";
        int mid = entries.size() / 2;
        double firstHalfAvg = entries.subList(0, mid).stream()
                .mapToDouble(MaterializedGroupEntry::totalValue).average().orElse(0);
        double secondHalfAvg = entries.subList(mid, entries.size()).stream()
                .mapToDouble(MaterializedGroupEntry::totalValue).average().orElse(0);
        if (firstHalfAvg <= 0) return "STABLE";

        double changePct = (secondHalfAvg - firstHalfAvg) / firstHalfAvg * 100;

        // Volatility check
        double mean   = entries.stream().mapToDouble(MaterializedGroupEntry::totalValue).average().orElse(0);
        double stdDev = Math.sqrt(entries.stream()
                .mapToDouble(e -> Math.pow(e.totalValue() - mean, 2)).average().orElse(0));
        double cv = mean > 0 ? stdDev / mean : 0;
        if (cv > 0.5) return "VOLATILE";

        if (changePct >  15) return "RISING";
        if (changePct < -15) return "FALLING";
        return "STABLE";
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private int compareTemporalKeys(String a, String b) {
        try { return Integer.compare(Integer.parseInt(a.trim()), Integer.parseInt(b.trim())); }
        catch (NumberFormatException e) { return a.compareToIgnoreCase(b); }
    }

    private String buildSummary(String dimension, MaterializedGroupEntry peak,
                                MaterializedGroupEntry trough, double volatility,
                                String momentum, int count, double mean) {
        return String.format(
                "%s pattern (%d periods): peak at '%s' (%.2f), trough at '%s' (%.2f). " +
                "Volatility=%.2f, Momentum=%s. Peak is %.1fx the trough.",
                dimension, count,
                peak.entityKey(), peak.totalValue(),
                trough.entityKey(), trough.totalValue(),
                volatility, momentum,
                trough.totalValue() > 0 ? peak.totalValue() / trough.totalValue() : 0);
    }
}
