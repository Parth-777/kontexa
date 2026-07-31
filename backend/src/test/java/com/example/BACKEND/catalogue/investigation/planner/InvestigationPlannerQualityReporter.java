package com.example.BACKEND.catalogue.investigation.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates planner quality validation results and writes human-readable reports.
 */
public final class InvestigationPlannerQualityReporter {

    private InvestigationPlannerQualityReporter() {}

    public static Summary summarize(List<InvestigationObjectiveQualityValidator.ValidationResult> results) {
        int total = results.size();
        long pass = results.stream().filter(InvestigationObjectiveQualityValidator.ValidationResult::pass).count();
        long contractOk = results.stream()
                .filter(InvestigationObjectiveQualityValidator.ValidationResult::contractComplete).count();
        long bindingOk = results.stream()
                .filter(InvestigationObjectiveQualityValidator.ValidationResult::catalogueBound).count();
        long comparisonOk = results.stream()
                .filter(InvestigationObjectiveQualityValidator.ValidationResult::comparisonValid).count();
        long exclusionOk = results.stream()
                .filter(InvestigationObjectiveQualityValidator.ValidationResult::exclusionValid).count();
        long ambiguous = results.stream()
                .filter(InvestigationObjectiveQualityValidator.ValidationResult::ambiguous).count();

        Map<String, Long> issueFrequency = results.stream()
                .flatMap(r -> r.issues().stream())
                .collect(Collectors.groupingBy(
                        InvestigationObjectiveQualityValidator.QualityIssue::code,
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<InvestigationObjectiveQualityValidator.ValidationResult> failures =
                results.stream().filter(r -> !r.pass()).toList();

        List<InvestigationObjectiveQualityValidator.ValidationResult> ambiguityCases =
                results.stream().filter(InvestigationObjectiveQualityValidator.ValidationResult::ambiguous).toList();

        return new Summary(
                total,
                pass,
                contractOk,
                bindingOk,
                comparisonOk,
                exclusionOk,
                ambiguous,
                issueFrequency,
                failures,
                ambiguityCases,
                results);
    }

    public static void writeJson(Summary summary, Path path, ObjectMapper mapper) throws IOException {
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(path.getParent());
        mapper.writeValue(path.toFile(), summary);
    }

    public static void writeMarkdown(Summary summary, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder md = new StringBuilder();
        md.append("# Investigation Planner Quality Report\n\n");
        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n|--------|-------|\n");
        md.append("| Questions | ").append(summary.totalQuestions()).append(" |\n");
        md.append("| Pass rate | ")
                .append(fmtPct(summary.passCount(), summary.totalQuestions())).append(" |\n");
        md.append("| Contract complete | ")
                .append(fmtPct(summary.contractCompleteCount(), summary.totalQuestions())).append(" |\n");
        md.append("| Catalogue bound | ")
                .append(fmtPct(summary.catalogueBoundCount(), summary.totalQuestions())).append(" |\n");
        md.append("| Comparison valid | ")
                .append(fmtPct(summary.comparisonValidCount(), summary.totalQuestions())).append(" |\n");
        md.append("| Exclusion valid | ")
                .append(fmtPct(summary.exclusionValidCount(), summary.totalQuestions())).append(" |\n");
        md.append("| Ambiguity cases | ").append(summary.ambiguityCount()).append(" |\n\n");

        md.append("## Recurring failure patterns\n\n");
        if (summary.issueFrequency().isEmpty()) {
            md.append("_No issues recorded._\n\n");
        } else {
            summary.issueFrequency().entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> md.append("- **").append(e.getKey()).append("**: ")
                            .append(e.getValue()).append("\n"));
            md.append("\n");
        }

        md.append("## Failures (").append(summary.failures().size()).append(")\n\n");
        for (var f : summary.failures()) {
            md.append("### ").append(f.question()).append("\n\n");
            md.append("- execution_mode: `").append(nullSafe(f.executionMode())).append("`\n");
            for (var issue : f.issues()) {
                md.append("- `").append(issue.code()).append("`: ").append(issue.detail()).append("\n");
            }
            md.append("\n");
        }

        md.append("## Ambiguity cases (").append(summary.ambiguityCases().size()).append(")\n\n");
        for (var a : summary.ambiguityCases()) {
            md.append("- ").append(a.question());
            if (a.objective() != null) {
                md.append(" (confidence=").append(a.objective().semanticConfidence()).append(")");
            }
            md.append("\n");
        }

        Files.writeString(path, md);
    }

    private static String fmtPct(long count, int total) {
        if (total == 0) {
            return "0%";
        }
        return String.format("%.1f%% (%d/%d)", 100.0 * count / total, count, total);
    }

    private static String nullSafe(String s) {
        return s == null ? "null" : s;
    }

    public record Summary(
            int totalQuestions,
            long passCount,
            long contractCompleteCount,
            long catalogueBoundCount,
            long comparisonValidCount,
            long exclusionValidCount,
            long ambiguityCount,
            Map<String, Long> issueFrequency,
            List<InvestigationObjectiveQualityValidator.ValidationResult> failures,
            List<InvestigationObjectiveQualityValidator.ValidationResult> ambiguityCases,
            List<InvestigationObjectiveQualityValidator.ValidationResult> allResults
    ) {}
}
