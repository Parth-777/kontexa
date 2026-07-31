package com.example.BACKEND.catalogue.investigation.planner;

import com.example.BACKEND.catalogue.investigation.InvestigationObjective;
import com.example.BACKEND.catalogue.investigation.InvestigationObjectiveNormalizer;
import com.example.BACKEND.catalogue.investigation.InvestigationObjectiveParser;
import com.example.BACKEND.catalogue.semantic.phase2.ApprovedCatalogueSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredCompletionClient;
import com.example.BACKEND.catalogue.semantic.phase2.GptStructuredSemanticPlanner;
import com.example.BACKEND.catalogue.semantic.phase2.SchemaSnapshot;
import com.example.BACKEND.catalogue.semantic.phase2.StructuredSemanticPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live planner quality audit: runs 50+ investigation questions through GPT,
 * normalizes InvestigationObjective via {@link InvestigationObjectiveNormalizer},
 * validates contract quality, and writes reports under {@code target/}.
 *
 * <p>Skipped when no OpenAI API key is configured. Does not modify Phase 1 runtime code.
 */
class InvestigationPlannerQualityLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Investigation planner quality suite (50+ questions)")
    void auditInvestigationPlannerQuality() throws Exception {
        String apiKey = resolveProperty("OPENAI_API_KEY", "openai.api.key");
        String model = resolveProperty("OPENAI_MODEL", "openai.model");
        assumeTrue(isUsableKey(apiKey), "No usable OpenAI API key — skipping live planner quality audit");

        GptStructuredCompletionClient client = new GptStructuredCompletionClient(apiKey, model, MAPPER);
        GptStructuredSemanticPlanner planner = new GptStructuredSemanticPlanner(client, MAPPER);
        InvestigationObjectiveParser objectiveParser = new InvestigationObjectiveParser();
        InvestigationObjectiveNormalizer normalizer = new InvestigationObjectiveNormalizer();
        InvestigationObjectiveQualityValidator validator = new InvestigationObjectiveQualityValidator();

        ApprovedCatalogueSnapshot catalogue = InvestigationPlannerFixtures.catalogue();
        SchemaSnapshot schema = InvestigationPlannerFixtures.schema();
        List<String> questions = InvestigationPlannerQuestionBank.questions();

        assertTrue(questions.size() >= 50, "question bank must contain at least 50 questions");

        List<InvestigationObjectiveQualityValidator.ValidationResult> results = new ArrayList<>();
        for (String question : questions) {
            try {
                StructuredSemanticPlan plan = planner.plan(question, catalogue, schema);
                InvestigationObjective parsed = plan.investigationObjective() == null
                        ? null
                        : objectiveParser.parse(plan.investigationObjective());

                InvestigationObjectiveNormalizer.NormalizationResult normalized =
                        normalizer.normalize(parsed, catalogue);
                if (!normalized.ok()) {
                    results.add(new InvestigationObjectiveQualityValidator.ValidationResult(
                            question,
                            plan.executionMode(),
                            null,
                            List.of(new InvestigationObjectiveQualityValidator.QualityIssue(
                                    "NORMALIZATION_REJECTED", normalized.reason())),
                            false,
                            false,
                            false,
                            false,
                            false));
                    continue;
                }

                results.add(validator.validate(question, plan, normalized.objective(), catalogue));
            } catch (Exception e) {
                results.add(new InvestigationObjectiveQualityValidator.ValidationResult(
                        question,
                        null,
                        null,
                        List.of(new InvestigationObjectiveQualityValidator.QualityIssue(
                                "PLANNER_ERROR", e.getMessage())),
                        false,
                        false,
                        false,
                        false,
                        false));
            }
        }

        InvestigationPlannerQualityReporter.Summary summary =
                InvestigationPlannerQualityReporter.summarize(results);

        Path json = Path.of("target/investigation-planner-quality-report.json");
        Path md = Path.of("target/investigation-planner-quality-summary.md");
        InvestigationPlannerQualityReporter.writeJson(summary, json, MAPPER);
        InvestigationPlannerQualityReporter.writeMarkdown(summary, md);

        System.out.printf("""
                Investigation planner quality audit (n=%d)
                Pass rate: %.1f%% (%d/%d)
                Contract complete: %.1f%%
                Catalogue bound: %.1f%%
                Comparison valid: %.1f%%
                Exclusion valid: %.1f%%
                Ambiguity cases: %d
                JSON: %s
                Markdown: %s
                """,
                summary.totalQuestions(),
                100.0 * summary.passCount() / summary.totalQuestions(),
                summary.passCount(), summary.totalQuestions(),
                100.0 * summary.contractCompleteCount() / summary.totalQuestions(),
                100.0 * summary.catalogueBoundCount() / summary.totalQuestions(),
                100.0 * summary.comparisonValidCount() / summary.totalQuestions(),
                100.0 * summary.exclusionValidCount() / summary.totalQuestions(),
                summary.ambiguityCount(),
                json.toAbsolutePath(),
                md.toAbsolutePath());

        summary.issueFrequency().forEach((code, count) ->
                System.out.println("  " + code + ": " + count));

        assertEquals(questions.size(), summary.totalQuestions());
        assertTrue(json.toFile().exists());
        assertTrue(md.toFile().exists());
        assertTrue(100.0 * summary.passCount() / summary.totalQuestions() >= 90.0,
                "normalized pass rate must be >= 90%");
    }

    private static String resolveProperty(String envKey, String propKey) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromSysProp = System.getProperty(propKey);
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return fromSysProp.trim();
        }
        return fromClasspathProperties(propKey);
    }

    private static String fromClasspathProperties(String propKey) {
        try (InputStream in = InvestigationPlannerQualityLiveTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty(propKey);
            return value != null ? value.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isUsableKey(String apiKey) {
        return apiKey != null
                && apiKey.startsWith("sk-")
                && apiKey.length() > 20
                && !apiKey.contains("YOUR")
                && !apiKey.contains("placeholder");
    }
}
