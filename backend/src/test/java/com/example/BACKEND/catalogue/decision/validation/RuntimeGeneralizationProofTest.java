package com.example.BACKEND.catalogue.decision.validation;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.UniversalAnalysisPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime proof with questions NOT present in any regression bank.
 *
 * Run: ./mvnw test -Dtest=RuntimeGeneralizationProofTest
 * Log: target/runtime-generalization-proof.log
 */
class RuntimeGeneralizationProofTest {

    private static final Path LOG_FILE = Path.of("target", "runtime-generalization-proof.log");
    private static PrintWriter log;

    private QuestionSemanticExtractor extractor;
    private MetricResolutionEngine metricEngine;
    private QuestionInvestigationPlanner investigationPlanner;
    private UniversalAnalysisPlanner analysisPlanner;
    private DeterministicAnalyticalQueryPlanner sqlPlanner;

    private final Map<String, RegistryResolutionBundle> bundles = new LinkedHashMap<>();

    record ProofCase(String dataset, String question) {}

  /** Round-2 unseen questions — distinct from regression, validation, and round-1 proof bank. */
    private static final List<ProofCase> CASES = List.of(
            new ProofCase("facility_operations",
                    "Factory line bearing the heaviest unit cost load?"),
            new ProofCase("facility_operations",
                    "Defect counts climbing with output throughput?"),
            new ProofCase("facility_operations",
                    "Manufacturing output swings across weekly reports"),

            new ProofCase("subscription_events",
                    "Hourly cancellation spikes over event hours?"),
            new ProofCase("subscription_events",
                    "Payment receipts versus session minutes — any connection?"),
            new ProofCase("subscription_events",
                    "Subscription tier slice of total payments"),

            new ProofCase("weather_observations",
                    "Barometer pressure contrasted by climate region"),
            new ProofCase("weather_observations",
                    "Rain accumulation tied to gust velocity?"),
            new ProofCase("weather_observations",
                    "Top rainfall stations in the network"),

            new ProofCase("hospital_bed_flow",
                    "Treatment billing sway from longer patient stays?"),
            new ProofCase("hospital_bed_flow",
                    "Readmissions apportioned by acuity tier"),
            new ProofCase("hospital_bed_flow",
                    "Which care unit has lengthiest patient stays?"),

            new ProofCase("esports_matches",
                    "Do prize purses scale with peak viewership?"),
            new ProofCase("esports_matches",
                    "Viewership levels traced across match hours"),
            new ProofCase("esports_matches",
                    "Prize payout composition by team geography"),

            new ProofCase("satellite_telemetry",
                    "Relay station power consumption across months"),
            new ProofCase("satellite_telemetry",
                    "Signal integrity versus orbital drift offset?"),
            new ProofCase("satellite_telemetry",
                    "Which spacecraft shows feeble signal transmission?"),

            new ProofCase("vineyard_production",
                    "Fermentation output progression by harvest week"),
            new ProofCase("vineyard_production",
                    "Harvest tonnage against sugar brix — how related?")
    );

    @BeforeAll
    static void openLog() throws IOException {
        Files.createDirectories(LOG_FILE.getParent());
        log = new PrintWriter(Files.newBufferedWriter(LOG_FILE));
        log.println("Runtime generalization proof — " + java.time.Instant.now());
        log.flush();
    }

    @org.junit.jupiter.api.AfterAll
    static void closeLog() {
        if (log != null) {
            log.flush();
            log.close();
        }
    }

    @BeforeEach
    void setUp() {
        extractor = MetricResolutionTestSupport.extractor();
        metricEngine = MetricResolutionTestSupport.engine();
        investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        analysisPlanner = UniversalPlannerTestSupport.universalPlanner();
        sqlPlanner = SqlTemplateTestHarness.create().planner;
        bundles.clear();
        for (ValidationDatasetRegistry.DatasetDef def : ValidationDatasetRegistry.all()) {
            bundles.put(def.name(), def.bundle());
        }
    }

    @Test
    void printRuntimeProofForUnseenQuestions() {
        StringBuilder all = new StringBuilder();
        all.append("RUNTIME GENERALIZATION PROOF — ").append(CASES.size()).append(" unseen questions\n");
        all.append("Datasets covered: ").append(CASES.stream().map(ProofCase::dataset).distinct().count()).append('\n');
        all.append("=".repeat(100)).append('\n');

        int passed = 0;
        List<String> blocked = new ArrayList<>();
        int n = 0;
        for (ProofCase c : CASES) {
            n++;
            CaseResult result = traceCase(n, c);
            all.append(result.trace());
            if (result.executable()) {
                passed++;
            } else {
                blocked.add("CASE " + n + " [" + c.dataset() + "]: " + c.question()
                        + " — " + result.blocker());
            }
        }

        all.append("\n").append("=".repeat(100)).append('\n');
        all.append("SCORE: ").append(passed).append('/').append(CASES.size()).append(" executable\n");
        if (blocked.isEmpty()) {
            all.append("BLOCKED: none\n");
        } else {
            all.append("BLOCKED (").append(blocked.size()).append("):\n");
            for (String b : blocked) {
                all.append("  ").append(b).append('\n');
            }
        }

        String output = all.toString();
        System.out.print(output);
        if (log != null) {
            log.print(output);
            log.flush();
        }
    }

    private record CaseResult(String trace, boolean executable, String blocker) {}

    private CaseResult traceCase(int index, ProofCase c) {
        RegistryResolutionBundle bundle = bundles.get(c.dataset());
        String question = c.question();

        QuestionSemantics semantics = extractor.extract(question, bundle);
        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
        AnalysisPlan plan = analysisPlanner.plan(question, bundle, investigation, resolution, List.of());

        String sql = "";
        if (plan.executable()) {
            List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
            sql = specs.stream().map(QuerySpec::sql).reduce("", String::concat);
        }

        StringBuilder out = new StringBuilder();
        out.append("\n").append("=".repeat(100)).append('\n');
        out.append("CASE ").append(index).append(" [").append(c.dataset()).append("]\n");
        out.append("question: ").append(question).append('\n');
        out.append('\n');

        out.append("QuestionSemantics (runtime object):\n");
        out.append("  ").append(semantics).append('\n');
        out.append("  primaryMetric=").append(semantics.primaryMetric()).append('\n');
        out.append("  targetMetric=").append(semantics.targetMetric()).append('\n');
        out.append("  dimension=").append(semantics.dimension()).append('\n');
        out.append("  grouping=").append(semantics.grouping()).append('\n');
        out.append("  intent=").append(semantics.intent()).append('\n');
        out.append("  relationship=").append(semantics.relationship()).append('\n');
        out.append("  extractedEntities=").append(semantics.extractedEntities()).append('\n');
        out.append('\n');

        out.append("MetricResolution (runtime object):\n");
        out.append("  ").append(resolution).append('\n');
        out.append("  resolved metric=").append(resolution.primaryMetric()).append('\n');
        out.append("  resolved dimension=").append(resolution.dimension()).append('\n');
        out.append("  relationshipVariable=").append(resolution.relationshipVariable()).append('\n');
        out.append("  rejected=").append(resolution.rejected())
                .append(resolution.rejected() ? " reason=" + resolution.rejectionReason() : "")
                .append('\n');
        out.append('\n');

        out.append("QuestionInvestigation.extraction.intent (resolved intent):\n");
        out.append("  ").append(investigation.extraction() != null
                ? investigation.extraction().intent() : "null").append('\n');
        out.append("  investigation metricKey=").append(investigation.extraction() != null
                ? investigation.extraction().metricKey() : "null").append('\n');
        out.append("  investigation dimension=").append(investigation.dimension() != null
                && investigation.dimension().resolved()
                ? investigation.dimension().columnKey() : "unresolved").append('\n');
        out.append('\n');

        out.append("AnalysisPlan (runtime object):\n");
        out.append("  ").append(plan).append('\n');
        out.append("  intent=").append(plan.intent()).append('\n');
        out.append("  primaryMetric=").append(plan.primaryMetric()).append('\n');
        out.append("  dimension=").append(plan.dimension()).append('\n');
        out.append("  groupingAlias=").append(plan.groupingAlias()).append('\n');
        out.append("  relationshipVariable=").append(plan.relationshipVariable()).append('\n');
        out.append("  executable=").append(plan.executable());
        if (!plan.executable()) {
            out.append(" blocker=").append(plan.blockingReason());
        }
        out.append('\n');
        out.append('\n');

        out.append("generated SQL:\n");
        out.append(sql.isBlank() ? "(none — plan not executable or empty specs)\n" : sql + "\n");

        String blocker = plan.executable() ? "" : plan.blockingReason();
        return new CaseResult(out.toString(), plan.executable(), blocker);
    }
}
