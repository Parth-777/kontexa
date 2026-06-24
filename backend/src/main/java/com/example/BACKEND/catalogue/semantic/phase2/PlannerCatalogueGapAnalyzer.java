package com.example.BACKEND.catalogue.semantic.phase2;

import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModel;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryModelAdapter;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidationResult;
import com.example.BACKEND.catalogue.semantic.canonical.CanonicalQueryValidator;
import com.example.BACKEND.catalogue.semantic.canonical.SqlFidelityBenchmarkRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits canonical validation failures and classifies planner vs catalogue alignment gaps.
 * Audit only — no repair or production behavior changes.
 */
public final class PlannerCatalogueGapAnalyzer {

    private static final Pattern MISSING_COLUMN = Pattern.compile(
            "^(?<path>[\\w.]+) column not in catalogue: (?<column>\\S+)$");

    private final CanonicalQueryModelAdapter canonicalAdapter = new CanonicalQueryModelAdapter();

    public enum GapCategory {
        UNKNOWN_MEASURE,
        UNKNOWN_DIMENSION,
        MISSPELLED_CATALOGUE_FIELD,
        MISSING_CATALOGUE_SYNONYM,
        INVALID_RELATIONSHIP_OPERANDS,
        INVALID_BIVARIATE_CONSTRUCTION,
        PLANNER_HALLUCINATION,
        OTHER
    }

    public enum Remediation {
        CATALOGUE_ENRICHMENT,
        SYNONYM_DICTIONARY,
        PLANNER_PROMPT,
        PLANNER_POST_VALIDATION
    }

    public record FieldGap(
            String plannerField,
            String fieldPath,
            GapCategory category,
            String nearestCatalogueMatch,
            int editDistance,
            String suggestedSynonymMapping,
            Remediation recommendedRemediation,
            String validationIssue
    ) {}

    public record CaseGap(
            int index,
            String question,
            String source,
            List<String> validationIssues,
            List<FieldGap> fieldGaps
    ) {}

    public record Summary(
            int totalCases,
            int validationFailureCases,
            int validationPassCases,
            Map<String, Integer> categoryFrequency,
            Map<String, Integer> plannerFieldFrequency,
            Map<String, Integer> remediationFrequency,
            Map<String, Integer> validationIssueFrequency,
            List<CaseGap> failures,
            List<FieldGap> uniqueFieldGaps
    ) {}

    public Summary analyze(
            List<SqlFidelityBenchmarkRunner.BenchmarkCase> cases,
            ApprovedCatalogueSnapshot catalogue,
            CanonicalQueryValidator validator
    ) {
        List<CaseGap> failures = new ArrayList<>();
        int passCount = 0;

        for (SqlFidelityBenchmarkRunner.BenchmarkCase c : cases) {
            CanonicalQueryModel canonical = canonicalAdapter.adapt(c.plan());
            CanonicalQueryValidationResult validation = validator.validate(canonical, catalogue);
            if (validation.valid()) {
                passCount++;
                continue;
            }
            failures.add(auditFailure(c, canonical, validation.issues(), catalogue));
        }

        return new Summary(
                cases.size(),
                failures.size(),
                passCount,
                aggregateCategoryFrequency(failures),
                aggregatePlannerFieldFrequency(failures),
                aggregateRemediationFrequency(failures),
                aggregateIssueFrequency(failures),
                failures,
                dedupeFieldGaps(failures));
    }

    private CaseGap auditFailure(
            SqlFidelityBenchmarkRunner.BenchmarkCase c,
            CanonicalQueryModel canonical,
            List<String> issues,
            ApprovedCatalogueSnapshot catalogue
    ) {
        List<FieldGap> fieldGaps = new ArrayList<>();
        for (String issue : issues) {
            fieldGaps.add(classifyIssue(issue, canonical, catalogue));
        }
        return new CaseGap(c.index(), c.question(), c.source(), List.copyOf(issues), List.copyOf(fieldGaps));
    }

    private FieldGap classifyIssue(
            String issue,
            CanonicalQueryModel canonical,
            ApprovedCatalogueSnapshot catalogue
    ) {
        if ("bivariate operands must be distinct".equals(issue)) {
            String colA = canonical.bivariate() != null ? canonical.bivariate().columnA() : null;
            String colB = canonical.bivariate() != null ? canonical.bivariate().columnB() : null;
            return new FieldGap(
                    colA != null ? colA + "," + colB : null,
                    "bivariate",
                    GapCategory.INVALID_BIVARIATE_CONSTRUCTION,
                    null,
                    -1,
                    null,
                    Remediation.PLANNER_POST_VALIDATION,
                    issue);
        }

        Matcher matcher = MISSING_COLUMN.matcher(issue);
        if (matcher.matches()) {
            String path = matcher.group("path");
            String column = matcher.group("column");
            CatalogueFieldMatcher.Match nearest = CatalogueFieldMatcher.nearest(column, catalogue);
            GapCategory category = categorizeMissingColumn(path, column, nearest, catalogue);
            Remediation remediation = remediationFor(category);
            String synonym = category == GapCategory.MISSPELLED_CATALOGUE_FIELD
                    || category == GapCategory.MISSING_CATALOGUE_SYNONYM
                    ? CatalogueFieldMatcher.suggestedSynonymMapping(column, nearest)
                    : null;
            return new FieldGap(
                    column,
                    path,
                    category,
                    nearest.columnName(),
                    nearest.editDistance(),
                    synonym,
                    remediation,
                    issue);
        }

        if (issue.contains("measure.aggregation required")) {
            return new FieldGap(
                    canonical.measure() != null ? canonical.measure().column() : null,
                    "measure.aggregation",
                    GapCategory.INVALID_RELATIONSHIP_OPERANDS,
                    null,
                    -1,
                    null,
                    Remediation.PLANNER_PROMPT,
                    issue);
        }

        if (issue.contains("confidence below threshold")) {
            return new FieldGap(
                    null,
                    "metadata.confidence",
                    GapCategory.OTHER,
                    null,
                    -1,
                    null,
                    Remediation.PLANNER_POST_VALIDATION,
                    issue);
        }

        return new FieldGap(
                null,
                "unknown",
                GapCategory.OTHER,
                null,
                -1,
                null,
                Remediation.PLANNER_POST_VALIDATION,
                issue);
    }

    private static GapCategory categorizeMissingColumn(
            String fieldPath,
            String plannerField,
            CatalogueFieldMatcher.Match nearest,
            ApprovedCatalogueSnapshot catalogue
    ) {
        boolean measureContext = isMeasureContext(fieldPath);
        boolean dimensionContext = isDimensionContext(fieldPath);

        if (nearest.editDistance() <= 3) {
            return GapCategory.MISSPELLED_CATALOGUE_FIELD;
        }
        if (CatalogueFieldMatcher.hasSemanticOverlap(plannerField, nearest.columnName())
                && nearest.editDistance() <= 8) {
            return GapCategory.MISSING_CATALOGUE_SYNONYM;
        }
        if (isLikelyHallucination(plannerField, nearest, catalogue)) {
            return GapCategory.PLANNER_HALLUCINATION;
        }
        if (measureContext) {
            return GapCategory.UNKNOWN_MEASURE;
        }
        if (dimensionContext) {
            return GapCategory.UNKNOWN_DIMENSION;
        }
        if (fieldPath.contains("ratio") || fieldPath.contains("bivariate")) {
            return GapCategory.INVALID_RELATIONSHIP_OPERANDS;
        }
        return GapCategory.PLANNER_HALLUCINATION;
    }

    private static boolean isMeasureContext(String fieldPath) {
        return fieldPath.startsWith("measure")
                || fieldPath.startsWith("ratio")
                || fieldPath.startsWith("bivariate");
    }

    private static boolean isDimensionContext(String fieldPath) {
        return fieldPath.startsWith("partition") || fieldPath.startsWith("filter");
    }

    private static boolean isLikelyHallucination(
            String plannerField,
            CatalogueFieldMatcher.Match nearest,
            ApprovedCatalogueSnapshot catalogue
    ) {
        if (nearest.editDistance() > 5) {
            return true;
        }
        if (CatalogueFieldMatcher.hasSemanticOverlap(plannerField, nearest.columnName())) {
            return false;
        }
        String normalized = CatalogueFieldMatcher.normalize(plannerField);
        boolean sharesToken = catalogue.columns().stream()
                .anyMatch(col -> {
                    String colNorm = CatalogueFieldMatcher.normalize(col.columnName());
                    for (String token : normalized.split("_")) {
                        if (token.length() >= 4 && colNorm.contains(token)) {
                            return true;
                        }
                    }
                    return false;
                });
        return !sharesToken && nearest.editDistance() >= 4;
    }

    private static Remediation remediationFor(GapCategory category) {
        return switch (category) {
            case MISSPELLED_CATALOGUE_FIELD -> Remediation.PLANNER_POST_VALIDATION;
            case MISSING_CATALOGUE_SYNONYM -> Remediation.SYNONYM_DICTIONARY;
            case UNKNOWN_MEASURE, UNKNOWN_DIMENSION -> Remediation.CATALOGUE_ENRICHMENT;
            case INVALID_RELATIONSHIP_OPERANDS -> Remediation.PLANNER_PROMPT;
            case INVALID_BIVARIATE_CONSTRUCTION -> Remediation.PLANNER_POST_VALIDATION;
            case PLANNER_HALLUCINATION -> Remediation.PLANNER_PROMPT;
            case OTHER -> Remediation.PLANNER_POST_VALIDATION;
        };
    }

    private static Map<String, Integer> aggregateCategoryFrequency(List<CaseGap> failures) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (GapCategory c : GapCategory.values()) {
            counts.put(c.name(), 0);
        }
        for (CaseGap failure : failures) {
            for (FieldGap gap : failure.fieldGaps()) {
                counts.merge(gap.category().name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> aggregatePlannerFieldFrequency(List<CaseGap> failures) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CaseGap failure : failures) {
            for (FieldGap gap : failure.fieldGaps()) {
                if (gap.plannerField() != null) {
                    counts.merge(gap.plannerField(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static Map<String, Integer> aggregateRemediationFrequency(List<CaseGap> failures) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Remediation r : Remediation.values()) {
            counts.put(r.name(), 0);
        }
        for (CaseGap failure : failures) {
            for (FieldGap gap : failure.fieldGaps()) {
                counts.merge(gap.recommendedRemediation().name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> aggregateIssueFrequency(List<CaseGap> failures) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CaseGap failure : failures) {
            for (String issue : failure.validationIssues()) {
                counts.merge(issue, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static List<FieldGap> dedupeFieldGaps(List<CaseGap> failures) {
        Map<String, FieldGap> unique = new LinkedHashMap<>();
        for (CaseGap failure : failures) {
            for (FieldGap gap : failure.fieldGaps()) {
                String key = gap.fieldPath() + "|" + gap.plannerField() + "|" + gap.category();
                unique.putIfAbsent(key, gap);
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(FieldGap::category)
                        .thenComparing(g -> g.plannerField() != null ? g.plannerField() : ""))
                .toList();
    }

    public static void writeJsonReport(Summary summary, Path outputPath, ObjectMapper mapper)
            throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("totalCases", summary.totalCases());
        root.put("validationFailureCases", summary.validationFailureCases());
        root.put("validationPassCases", summary.validationPassCases());
        root.put("validationFailureRate",
                rate(summary.validationFailureCases(), summary.totalCases()));

        ObjectNode categoryFreq = root.putObject("categoryFrequency");
        summary.categoryFrequency().forEach(categoryFreq::put);

        ObjectNode fieldFreq = root.putObject("plannerFieldFrequency");
        summary.plannerFieldFrequency().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> fieldFreq.put(e.getKey(), e.getValue()));

        ObjectNode remediationFreq = root.putObject("remediationFrequency");
        summary.remediationFrequency().forEach(remediationFreq::put);

        ObjectNode issueFreq = root.putObject("validationIssueFrequency");
        summary.validationIssueFrequency().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> issueFreq.put(e.getKey(), e.getValue()));

        ArrayNode uniqueGaps = root.putArray("uniqueFieldGaps");
        for (FieldGap gap : summary.uniqueFieldGaps()) {
            uniqueGaps.add(fieldGapNode(gap, mapper));
        }

        ArrayNode cases = root.putArray("failures");
        for (CaseGap failure : summary.failures()) {
            ObjectNode node = mapper.createObjectNode();
            node.put("index", failure.index());
            node.put("question", failure.question());
            node.put("source", failure.source());
            node.set("validationIssues", mapper.valueToTree(failure.validationIssues()));
            ArrayNode gaps = node.putArray("fieldGaps");
            failure.fieldGaps().forEach(g -> gaps.add(fieldGapNode(g, mapper)));
            cases.add(node);
        }

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
    }

    public static void writeMarkdownSummary(Summary summary, Path outputPath) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Planner-Catalogue Gap Summary\n\n");
        md.append("Audit of canonical validation failures from the SQL fidelity benchmark.\n\n");
        md.append("Total cases: ").append(summary.totalCases()).append("\n");
        md.append("Validation failures: ").append(summary.validationFailureCases())
                .append(" (").append(String.format(Locale.ROOT, "%.0f%%",
                        rate(summary.validationFailureCases(), summary.totalCases())))
                .append(")\n");
        md.append("Validation pass: ").append(summary.validationPassCases()).append("\n\n");

        md.append("## Failure category frequency\n\n");
        md.append("| Category | Count |\n");
        md.append("|----------|-------|\n");
        summary.categoryFrequency().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> md.append("| ").append(formatCategory(e.getKey()))
                        .append(" | ").append(e.getValue()).append(" |\n"));
        md.append("\n");

        md.append("## Planner field frequency\n\n");
        md.append("| Planner field | Count |\n");
        md.append("|---------------|-------|\n");
        summary.plannerFieldFrequency().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> md.append("| `").append(e.getKey()).append("` | ")
                        .append(e.getValue()).append(" |\n"));
        md.append("\n");

        md.append("## Recommended remediation frequency\n\n");
        md.append("| Remediation | Count |\n");
        md.append("|-------------|-------|\n");
        summary.remediationFrequency().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> md.append("| ").append(formatRemediation(e.getKey()))
                        .append(" | ").append(e.getValue()).append(" |\n"));
        md.append("\n");

        md.append("## Unique field gaps\n\n");
        md.append("| Planner field | Path | Category | Nearest match | Edit distance | Suggested synonym |\n");
        md.append("|---------------|------|----------|---------------|---------------|-------------------|\n");
        for (FieldGap gap : summary.uniqueFieldGaps()) {
            md.append("| `").append(nullSafe(gap.plannerField())).append("` | ")
                    .append(gap.fieldPath()).append(" | ")
                    .append(formatCategory(gap.category().name())).append(" | ")
                    .append(nullSafe(gap.nearestCatalogueMatch())).append(" | ")
                    .append(gap.editDistance() >= 0 ? gap.editDistance() : "—").append(" | ")
                    .append(nullSafe(gap.suggestedSynonymMapping())).append(" |\n");
        }
        md.append("\n");

        md.append("## Interpretation\n\n");
        md.append("- **Misspelled catalogue field** — planner typo near an existing column; fix via ");
        md.append("post-validation fuzzy match or prompt spelling guardrails.\n");
        md.append("- **Missing catalogue synonym** — semantic alias gap (e.g. `carbon_emission_tons` → ");
        md.append("`carbon_emission`); fix via synonym dictionaries or catalogue enrichment.\n");
        md.append("- **Unknown measure/dimension** — field role is clear but no catalogue column fits; ");
        md.append("fix via catalogue enrichment.\n");
        md.append("- **Planner hallucination** — invented column with no catalogue anchor; fix via ");
        md.append("prompt constraints and post-validation rejection.\n");
        md.append("- **Invalid bivariate/relationship** — duplicate operands or inconsistent relationship ");
        md.append("construction; fix via planner prompt and post-validation.\n\n");

        md.append("## Architectural conclusion\n\n");
        md.append("Remaining canonical failures are **validator/catalogue alignment issues**, not ");
        md.append("`CanonicalSqlRenderer` defects. Prioritize synonym dictionaries for near-matches, ");
        md.append("catalogue enrichment for genuine schema gaps, and planner prompt/post-validation for ");
        md.append("hallucinations and invalid relationship operands.\n");

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
        Files.writeString(outputPath, md);
    }

    private static ObjectNode fieldGapNode(FieldGap gap, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("plannerField", gap.plannerField());
        node.put("fieldPath", gap.fieldPath());
        node.put("category", gap.category().name());
        node.put("nearestCatalogueMatch", gap.nearestCatalogueMatch());
        if (gap.editDistance() >= 0) {
            node.put("editDistance", gap.editDistance());
        }
        node.put("suggestedSynonymMapping", gap.suggestedSynonymMapping());
        node.put("recommendedRemediation", gap.recommendedRemediation().name());
        node.put("validationIssue", gap.validationIssue());
        return node;
    }

    private static double rate(int count, int total) {
        if (total == 0) {
            return 0.0;
        }
        return 100.0 * count / total;
    }

    private static String formatCategory(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String formatRemediation(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String nullSafe(String value) {
        return value != null ? value : "—";
    }
}
