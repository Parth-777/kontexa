package com.example.BACKEND.catalogue.decision.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST API for the {@link ExecutionValidationHarness}.
 *
 * Endpoints:
 *   GET  /api/decision/validate               — run full golden suite
 *   GET  /api/decision/validate/category/{c}  — run by intent category
 *   GET  /api/decision/validate/case/{id}     — run a single test case
 *   GET  /api/decision/validate/summary       — compact pass/fail summary
 *
 * All endpoints return deterministic results with no warehouse calls.
 * Run these after deploying to verify analytical execution quality.
 */
@RestController
@RequestMapping("/api/decision/validate")
public class ValidationController {

    private static final Logger log = LoggerFactory.getLogger(ValidationController.class);
    private final ExecutionValidationHarness harness;
    private final SemanticParserValidationHarness semanticHarness;
    private final com.example.BACKEND.catalogue.decision.verification.GoldenAnalyticalVerificationHarness goldenHarness;

    public ValidationController(
            ExecutionValidationHarness harness,
            SemanticParserValidationHarness semanticHarness,
            com.example.BACKEND.catalogue.decision.verification.GoldenAnalyticalVerificationHarness goldenHarness
    ) {
        this.harness = harness;
        this.semanticHarness = semanticHarness;
        this.goldenHarness = goldenHarness;
    }

    /** Run mandatory golden analytical benchmarks on synthetic known-answer datasets. */
    @GetMapping("/golden-analytical")
    public ResponseEntity<Map<String, Object>> runGoldenAnalytical() {
        log.info("[validation-api] running golden analytical verification harness");
        var report = goldenHarness.runAll();
        return ResponseEntity.ok(goldenHarness.toMap(report));
    }

    /** Run semantic parser benchmark suite (NYC taxi analytical questions). */
    @GetMapping("/semantic")
    public ResponseEntity<Map<String, Object>> runSemanticSuite() {
        log.info("[validation-api] running semantic parser benchmark suite");
        SemanticParserValidationHarness.SuiteReport report = semanticHarness.runAll();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", report.total());
        response.put("passed", report.passed());
        response.put("failed", report.failed());
        response.put("passRate", String.format("%.1f%%", report.passRate() * 100));
        response.put("tests", report.results().stream().map(r -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id", r.id());
            line.put("question", r.question());
            line.put("passed", r.passed());
            line.put("resolved", r.resolved());
            if (!r.passed()) line.put("failures", r.failures());
            return line;
        }).collect(Collectors.toList()));
        return ResponseEntity.ok(response);
    }

    /** Run the full 54-case golden suite. Returns the complete validation report. */
    @GetMapping
    public ResponseEntity<ValidationReport> runFullSuite() {
        log.info("[validation-api] running full golden suite");
        ValidationReport report = harness.runFullSuite();
        log.info("[validation-api] done: {}/{} passed ({:.1f}%)",
                report.passed(), report.totalTests(),
                report.passRate() * 100);
        return ResponseEntity.ok(report);
    }

    /**
     * Run a subset of tests filtered by category.
     * Categories: RANKING, CONTRIBUTION, EFFICIENCY, TREND, SEGMENTATION, ANOMALY, COMPARISON, ROOT_CAUSE
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<ValidationReport> runByCategory(@PathVariable String category) {
        log.info("[validation-api] running category: {}", category);
        ValidationReport report = harness.runByCategory(category);
        return ResponseEntity.ok(report);
    }

    /** Run a single test case by ID (e.g. R01, C01, T04). */
    @GetMapping("/case/{id}")
    public ResponseEntity<?> runSingleCase(@PathVariable String id) {
        Optional<ExecutionInspectionLog> result = harness.runSingle(id);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result.get());
    }

    /**
     * Compact pass/fail summary — minimal response for quick health checks.
     * Returns overall stats plus a per-test pass/fail line.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> runSummary() {
        ValidationReport report = harness.runFullSuite();

        List<Map<String, Object>> testLines = report.logs().stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("id",       l.testCaseId());
            line.put("category", l.intentCategory());
            line.put("question", l.question());
            line.put("passed",   l.overallPassed());
            line.put("assertionsFailed", l.assertionsFailed());
            line.put("groupingDims", l.groupingDimensionLabels());
            line.put("generatedSql", l.generatedSqlStatements().size() + " stmt(s)");
            if (!l.overallPassed()) {
                line.put("failures", l.stageFailures().stream()
                        .map(sf -> sf.stageName() + ": " + sf.failureType())
                        .collect(Collectors.toList()));
            }
            return line;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTests",          report.totalTests());
        response.put("passed",              report.passed());
        response.put("failed",              report.failed());
        response.put("passRate",            String.format("%.1f%%", report.passRate() * 100));
        response.put("failuresByCategory",  report.failuresByCategory());
        response.put("failuresByStage",     report.failuresByStage());
        response.put("criticalGaps",        report.criticalGaps());
        response.put("tests",               testLines);

        return ResponseEntity.ok(response);
    }
}
