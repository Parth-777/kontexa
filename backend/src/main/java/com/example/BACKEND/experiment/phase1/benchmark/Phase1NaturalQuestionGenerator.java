package com.example.BACKEND.experiment.phase1.benchmark;

import com.example.BACKEND.catalogue.decision.planning.AnalysisIntent;
import com.example.BACKEND.experiment.phase1.Phase1ApprovedCatalogue;
import com.example.BACKEND.experiment.phase1.Phase1DatasetRegistry;
import com.example.BACKEND.experiment.phase1.Phase1FilterSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates 50+ natural-language factual questions per dataset.
 * Mixes column-style phrasing with business-language paraphrases.
 */
public final class Phase1NaturalQuestionGenerator {

    private static final int TARGET_PER_DATASET = 50;

    private Phase1NaturalQuestionGenerator() {}

    public static List<Phase1FactualCase> generateAll() {
        List<Phase1FactualCase> all = new ArrayList<>();
        for (Phase1DatasetRegistry.DatasetDef ds : Phase1DatasetRegistry.all()) {
            all.addAll(generateForDataset(ds.id()));
        }
        return List.copyOf(all);
    }

    public static List<Phase1FactualCase> generateForDataset(String datasetId) {
        List<Phase1ApprovedCatalogue.ColumnDef> cols = Phase1ApprovedCatalogue.columnsFor(datasetId);
        List<Phase1ApprovedCatalogue.ColumnDef> metrics = cols.stream()
                .filter(c -> "metric".equals(c.type())).toList();
        List<Phase1ApprovedCatalogue.ColumnDef> dimensions = cols.stream()
                .filter(c -> "dimension".equals(c.type())).toList();

        Set<String> seen = new LinkedHashSet<>();
        List<Phase1FactualCase> out = new ArrayList<>();
        int variant = 0;

        String[][] rankTemplates = {
                {"Top {n} {dim} by {metric}", "DESC"},
                {"Which {dim} has the highest {metric}?", "DESC"},
                {"Show {dim} ranked by {metric} descending", "DESC"},
                {"List {dim} with the greatest {metric}", "DESC"},
                {"What are the leading {dim} for {metric}?", "DESC"}
        };
        for (var m : metrics) {
            for (var d : dimensions) {
                for (String[] tpl : rankTemplates) {
                    if (out.size() >= TARGET_PER_DATASET) break;
                    add(out, seen, datasetId, tpl[0], m, d, variant++,
                            "SUM", tpl[1], 10, AnalysisIntent.RANKING);
                }
            }
        }

        String[][] lowTemplates = {
                {"Which {dim} has the lowest {metric}?", "ASC"},
                {"Find the {dim} with the smallest {metric}", "ASC"},
                {"Bottom {dim} ranked by {metric}", "ASC"}
        };
        for (var m : metrics) {
            for (var d : dimensions) {
                for (String[] tpl : lowTemplates) {
                    if (out.size() >= TARGET_PER_DATASET) break;
                    add(out, seen, datasetId, tpl[0], m, d, variant++,
                            "SUM", tpl[1], null, AnalysisIntent.RANKING);
                }
            }
        }

        String[] breakdownTemplates = {
                "Show {metric} broken down by {dim}",
                "How is {metric} spread across {dim}?",
                "Total {metric} for each {dim}",
                "Split {metric} by {dim}",
                "{metric} per {dim}"
        };
        for (var m : metrics) {
            for (var d : dimensions) {
                for (String tpl : breakdownTemplates) {
                    if (out.size() >= TARGET_PER_DATASET) break;
                    add(out, seen, datasetId, tpl, m, d, variant++,
                            "SUM", null, null, AnalysisIntent.CONTRIBUTION);
                }
            }
        }

        for (var m : metrics) {
            for (var d : dimensions) {
                if (!isTemporal(d.column())) continue;
                String[] trendTemplates = {
                        "How does {metric} change over {dim}?",
                        "Track {metric} across {dim}",
                        "{metric} trend by {dim}"
                };
                for (String tpl : trendTemplates) {
                    if (out.size() >= TARGET_PER_DATASET) break;
                    add(out, seen, datasetId, tpl, m, d, variant++,
                            "SUM", null, null, AnalysisIntent.TREND);
                }
            }
        }

        String[] filterTemplates = {
                "What is the total {metric} where {dim} is {value}?",
                "Sum {metric} for {dim} = {value}",
                "Give me {metric} filtered to {dim} {value}"
        };
        for (var m : metrics) {
            for (var d : dimensions) {
                if (d.sampleFilterValues().isEmpty()) continue;
                for (String value : d.sampleFilterValues()) {
                    for (String tpl : filterTemplates) {
                        if (out.size() >= TARGET_PER_DATASET) break;
                        String q = fill(tpl, m, d, variant++, value);
                        if (!seen.add(q)) continue;
                        out.add(new Phase1FactualCase(
                                datasetId, q, m.column(), null,
                                List.of(new Phase1FilterSpec(d.column(), "=", value)),
                                "SUM", null, null, AnalysisIntent.CONTRIBUTION, true));
                    }
                }
            }
        }

        int i = 0;
        while (out.size() < TARGET_PER_DATASET && !metrics.isEmpty() && !dimensions.isEmpty()) {
            var m = metrics.get(i % metrics.size());
            var d = dimensions.get(i % dimensions.size());
            String q = fill("Compare {metric} across different {dim}", m, d, variant++, null);
            if (seen.add(q)) {
                out.add(new Phase1FactualCase(
                        datasetId, q, m.column(), d.column(), List.of(),
                        "SUM", null, null, AnalysisIntent.COMPARISON, true));
            }
            i++;
        }

        return List.copyOf(out.subList(0, Math.min(TARGET_PER_DATASET, out.size())));
    }

    private static void add(
            List<Phase1FactualCase> out, Set<String> seen, String datasetId, String template,
            Phase1ApprovedCatalogue.ColumnDef metric, Phase1ApprovedCatalogue.ColumnDef dim,
            int variant, String agg, String orderDir, Integer limit, AnalysisIntent intent
    ) {
        String q = fill(template, metric, dim, variant, null);
        if (!seen.add(q)) return;
        out.add(new Phase1FactualCase(
                datasetId, q, metric.column(), dim.column(), List.of(),
                agg, orderDir, limit, intent, true));
    }

    private static String fill(
            String template, Phase1ApprovedCatalogue.ColumnDef metric,
            Phase1ApprovedCatalogue.ColumnDef dim, int variant, String filterValue
    ) {
        boolean business = variant % 3 == 1;
        String metricPhrase = phraseFor(metric, business);
        String dimPhrase = phraseFor(dim, business);
        return template
                .replace("{metric}", metricPhrase)
                .replace("{dim}", dimPhrase)
                .replace("{n}", "10")
                .replace("{value}", filterValue != null ? filterValue : "");
    }

    private static String phraseFor(Phase1ApprovedCatalogue.ColumnDef col, boolean business) {
        if (business) {
            String bp = Phase1ApprovedCatalogue.businessPhrase(col.description());
            if (!bp.isBlank()) return bp;
        }
        return humanize(col.column());
    }

    static String humanize(String column) {
        if (column == null) return "";
        return column.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static boolean isTemporal(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return lower.contains("week") || lower.contains("hour") || lower.contains("month")
                || lower.endsWith("_at");
    }
}
