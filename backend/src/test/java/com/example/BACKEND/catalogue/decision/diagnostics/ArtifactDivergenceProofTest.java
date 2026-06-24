package com.example.BACKEND.catalogue.decision.diagnostics;

import com.example.BACKEND.catalogue.decision.candidate.AnalyticalCandidate;
import com.example.BACKEND.catalogue.decision.candidate.CandidateAnalysisGenerator;
import com.example.BACKEND.catalogue.decision.candidate.CandidateExecutionOrchestrator;
import com.example.BACKEND.catalogue.decision.candidate.CandidateMaterializationExecutor;
import com.example.BACKEND.catalogue.decision.candidate.CandidateResultScorer;
import com.example.BACKEND.catalogue.decision.clarification.ResolvedAnalyticalQuestion;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.*;
import com.example.BACKEND.catalogue.decision.exploration.*;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.planning.*;
import com.example.BACKEND.catalogue.decision.reasoning.AnalyticalReasoningPlanner;
import com.example.BACKEND.catalogue.decision.reasoning.QuestionDrivenReasoningPlan;
import com.example.BACKEND.catalogue.decision.semantic.*;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import com.example.BACKEND.catalogue.decision.execution.framework.SchemaProfiler;
import com.example.BACKEND.catalogue.decision.execution.materialization.GroupByExecutor;
import com.example.BACKEND.catalogue.decision.execution.materialization.NumericDimensionBucketer;
import com.example.BACKEND.catalogue.decision.grounding.PresentationLabelResolver;
import com.example.BACKEND.catalogue.decision.governance.MetricSemanticRegistry;
import com.example.BACKEND.catalogue.decision.presentation.MetricBucketingEngine;
import com.example.BACKEND.catalogue.decision.transforms.DerivedDimensionRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Proves whether AnalysisPlan, InvestigationPlan, ResolvedAnalyticalQuestion,
 * and candidate winner disagree on metric / dimension / intent.
 */
class ArtifactDivergenceProofTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void compareArtifacts(String label, String question, RegistryResolutionBundle bundle) {
    IntentResolution intent = new IntentResolution(UUID.randomUUID(), "tenant", question, "GENERAL", 0.8);

    QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
    MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
    QuestionInvestigationPlanner investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
    UniversalAnalysisPlanner universalPlanner = UniversalPlannerTestSupport.universalPlanner();
    AnalyticalReasoningPlanner reasoningPlanner = new AnalyticalReasoningPlanner(
        new com.example.BACKEND.catalogue.decision.presentation.VisualizationStrategyEngine(),
        new DerivedDimensionRegistry());
    ExploratoryAnalysisPlanner exploratoryPlanner = buildExploratoryPlanner();
    AnalyticalPlanningEngine planningEngine = buildPlanningEngine();
    CandidateAnalysisGenerator candidateGenerator = buildCandidateGenerator();

    QuestionInvestigation investigation = investigationPlanner.plan(question, bundle);
    QuestionSemantics semantics = extractor.extract(question, bundle);
    MetricResolution resolution = metricEngine.resolve(semantics, bundle);
    QuestionDrivenReasoningPlan reasoningPlan = reasoningPlanner.plan(semantics, resolution);

    AnalysisPlan ap = universalPlanner.plan(question, bundle, investigation, resolution, List.of());
    ResolvedAnalyticalQuestion rq = exploratoryPlanner.plan(
        intent, bundle, semantics, resolution, reasoningPlan);
    InvestigationPlan ip;
    try {
      ip = planningEngine.plan(intent, ap, rq, reasoningPlan);
    } catch (AnalysisPlanProjectionException ex) {
      System.out.println("\n========== " + label + " ==========");
      System.out.println("Q: " + question);
      System.out.println("  PROJECTION BLOCKED: " + ex.getMessage());
      System.out.println("  AnalysisPlan executable=" + ap.executable()
          + " metric=" + ap.primaryMetric() + " dimension=" + ap.dimension());
      return;
    }
    List<AnalyticalCandidate> candidates = candidateGenerator.generate(intent, bundle, semantics, resolution);

    // Simulate warehouse rows aligned with AnalysisPlan SQL output (schema path authority)
    ComputationResultSet results = syntheticRows(ap, bundle);
    CandidateExecutionOrchestrator orchestrator = buildCandidateOrchestrator();
    CandidateExecutionOrchestrator.SelectionResult selection =
        orchestrator.executeAndSelect(results, candidates, resolution);

    InterpretationCandidatePlan winnerPlan = selection.hasWinner()
        ? selection.winner().candidate().plan() : null;

    printComparison(label, question, ap, rq, ip, candidates, winnerPlan, selection);
  }

  private void printComparison(
      String label, String question,
      AnalysisPlan ap, ResolvedAnalyticalQuestion rq, InvestigationPlan ip,
      List<AnalyticalCandidate> candidates,
      InterpretationCandidatePlan winnerPlan,
      CandidateExecutionOrchestrator.SelectionResult selection) {

    String apMetric = ap.primaryMetric();
    String apDim = ap.dimension();
    String apIntent = ap.intent().name();

    String rqMetric = rq.assumption().primaryMetric();
    String rqDim = blank(rq.assumption().grouping());
    String rqIntent = rq.assumption().intent().canonical().name();

    String ipIntent = ip.intentType().name();
    String ipMetric = ip.reasoningPlan() != null && ip.reasoningPlan().metricBinding() != null
        ? ip.reasoningPlan().metricBinding().metricColumn() : null;
    String ipDim = ip.reasoningPlan() != null && ip.reasoningPlan().metricBinding() != null
        ? ip.reasoningPlan().metricBinding().groupingColumn() : null;

    String winMetric = winnerPlan != null ? winnerPlan.primaryMetric() : null;
    String winDim = winnerPlan != null ? blank(winnerPlan.grouping()) : null;
    String winIntent = winnerPlan != null ? winnerPlan.intent().canonical().name() : null;

    boolean metricDisagree = !eq(apMetric, rqMetric) || !eq(apMetric, ipMetric)
        || (winMetric != null && !eq(apMetric, winMetric));
    boolean dimDisagree = !eq(apDim, rqDim) || !eq(apDim, ipDim)
        || (winDim != null && !eq(apDim, winDim));
    boolean intentDisagree = !eq(apIntent, rqIntent) || !eq(apIntent, ipIntent)
        || (winIntent != null && !eq(apIntent, winIntent));

    System.out.println("\n========== " + label + " ==========");
    System.out.println("Q: " + question);
    System.out.printf("  AnalysisPlan          metric=%-20s dim=%-25s intent=%s executable=%s%n",
        apMetric, apDim, apIntent, ap.executable());
    System.out.printf("  ResolvedQuestion        metric=%-20s dim=%-25s intent=%s%n",
        rqMetric, rqDim, rqIntent);
    System.out.printf("  InvestigationPlan       metric=%-20s dim=%-25s intent=%s%n",
        ipMetric, ipDim, ipIntent);
    if (!candidates.isEmpty()) {
      var c0 = candidates.getFirst().plan();
      System.out.printf("  Candidate[0]            metric=%-20s dim=%-25s intent=%s label=%s%n",
          c0.primaryMetric(), blank(c0.grouping()), c0.intent().canonical().name(), c0.label());
    }
    if (selection.hasWinner()) {
      System.out.printf("  CandidateWinner         metric=%-20s dim=%-25s intent=%s label=%s score=%.3f%n",
          winMetric, winDim, winIntent, winnerPlan.label(), selection.winner().totalScore());
    } else {
      System.out.println("  CandidateWinner         (none — no scored winner)");
    }
    System.out.printf("  DISAGREE metric=%s dimension=%s intent=%s%n",
        metricDisagree, dimDisagree, intentDisagree);

    if (metricDisagree || dimDisagree || intentDisagree) {
      System.out.println("  >>> DIVERGENCE <<<");
      traceFirstDivergence(apMetric, apDim, apIntent, rqMetric, rqDim, rqIntent,
          ipMetric, ipDim, ipIntent, winMetric, winDim, winIntent);
    }
  }

  private void traceFirstDivergence(
      String apM, String apD, String apI,
      String rqM, String rqD, String rqI,
      String ipM, String ipD, String ipI,
      String winM, String winD, String winI) {
    if (!eq(apM, rqM)) System.out.println("      1st: AnalysisPlan vs ResolvedQuestion — metric");
    else if (!eq(apD, rqD)) System.out.println("      1st: AnalysisPlan vs ResolvedQuestion — dimension");
    else if (!eq(apI, rqI)) System.out.println("      1st: AnalysisPlan vs ResolvedQuestion — intent");
    else if (!eq(rqM, ipM)) System.out.println("      1st: ResolvedQuestion vs InvestigationPlan — metric");
    else if (!eq(rqD, ipD)) System.out.println("      1st: ResolvedQuestion vs InvestigationPlan — dimension");
    else if (!eq(rqI, ipI)) System.out.println("      1st: ResolvedQuestion vs InvestigationPlan — intent");
    else if (winM != null && !eq(apM, winM))
      System.out.println("      1st: AnalysisPlan vs CandidateWinner — metric");
    else if (winD != null && !eq(apD, winD))
      System.out.println("      1st: AnalysisPlan vs CandidateWinner — dimension");
    else if (winI != null && !eq(apI, winI))
      System.out.println("      1st: AnalysisPlan vs CandidateWinner — intent");
  }

  /** Rows shaped like AnalysisPlan SQL so winner reflects candidate-list disagreement, not row shape. */
  private ComputationResultSet syntheticRows(AnalysisPlan ap, RegistryResolutionBundle bundle) {
    String table = bundle.entities().getFirst().tableRef();
    String dimCol = ap.dimension() != null ? ap.dimension() : "segment";
    String metricCol = ap.primaryMetric() != null ? ap.primaryMetric() : "total_amount";
    // Include bucket aliases used by legacy path so candidate materialization can score.
    List<Map<String, Object>> rows = List.of(
        row(table, dimCol, metricCol, "A", "weekend", "Z1", "Math"),
        row(table, dimCol, metricCol, "B", "weekday", "Z2", "Science"),
        row(table, dimCol, metricCol, "C", "weekend", "Z3", "History"));
    return new ComputationResultSet(UUID.randomUUID(), List.of(new QueryResult("q1", rows, 0L)), Map.of());
  }

  private static Map<String, Object> row(
      String table, String dimCol, String metricCol,
      String dimVal, String weekend, String zone, String subject) {
    Map<String, Object> m = new HashMap<>();
    m.put(dimCol, dimVal);
    m.put(dimCol + "_bucket", dimVal);
    m.put(metricCol, dimVal.equals("A") ? 100.0 : dimVal.equals("B") ? 80.0 : 60.0);
    m.put("weekend_flag", weekend);
    m.put("weekend_flag_bucket", weekend);
    m.put("pickup_zone", zone);
    m.put("pickup_zone_bucket", zone);
    m.put("subject", subject);
    m.put("subject_bucket", subject);
    m.put("relationship", "trip_distance");
    m.put("entity", table);
    return m;
  }

  static Stream<Arguments> scenarios() {
    String studentTable = "student_records";
    RegistryResolutionBundle student = new RegistryResolutionBundle(
        List.of(new EntityDescriptor("students", studentTable, List.of("id"), List.of("edu"))),
        List.of(
            new MetricDescriptor(studentTable + ".exam_score", "exam_score", "FLOAT", "AVG", null),
            new MetricDescriptor(studentTable + ".attendance_rate", "attendance_rate", "FLOAT", "AVG", null)),
        List.of(
            new DimensionDescriptor(studentTable + ".subject", "subject", "CATEGORICAL"),
            new DimensionDescriptor(studentTable + ".grade_level", "grade_level", "CATEGORICAL")),
        null);

    String taxiTable = "yellow_taxi_trips";
    RegistryResolutionBundle taxi = new RegistryResolutionBundle(
        List.of(new EntityDescriptor("taxi", taxiTable, List.of("id"), List.of("transport"))),
        List.of(
            new MetricDescriptor(taxiTable + ".total_amount", "total_amount", "FLOAT", "SUM", null),
            new MetricDescriptor(taxiTable + ".trip_distance", "trip_distance", "FLOAT", "SUM", null),
            new MetricDescriptor(taxiTable + ".tip_amount", "tip_amount", "FLOAT", "SUM", null)),
        List.of(
            new DimensionDescriptor(taxiTable + ".pickup_zone", "pickup_zone", "CATEGORICAL"),
            new DimensionDescriptor(taxiTable + ".trip_distance", "trip_distance", "NUMERIC")),
        null);

    return Stream.of(
        Arguments.of("student-ranking",
            "Which subjects have the highest exam performance?", student),
        Arguments.of("taxi-ranking",
            "Top pickup zones by revenue", taxi),
        Arguments.of("taxi-weekend-contribution",
            "How do weekend rides contribute to revenue?", taxi),
        Arguments.of("taxi-distance-impact",
            "How does trip distance affect revenue?", taxi),
        Arguments.of("vague-question",
            "How does something vague affect numbers?", taxi),
        Arguments.of("student-wrong-domain-question",
            "How do weekend rides contribute to revenue?", student)
    );
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static boolean eq(String a, String b) {
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (a.isBlank() && b.isBlank()) return true;
    return a.equals(b);
  }

  private static ExploratoryAnalysisPlanner buildExploratoryPlanner() {
    return new ExploratoryAnalysisPlanner(
        buildCandidateEngine(), buildCandidateGenerator(),
        new SoftSemanticValidator(
            new com.example.BACKEND.catalogue.decision.clarification.QueryViabilityChecker(
                new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy()),
            new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy()),
        new com.example.BACKEND.catalogue.decision.clarification.MetricFallbackHierarchy(),
        new MetricSemanticRegistry(new DomainAnalyticalDefaults()));
  }

  private static CandidateAnalysisGenerator buildCandidateGenerator() {
    var dictionary = new SemanticDictionary();
    var entityResolver = new QueryEntityResolver(dictionary);
    return new CandidateAnalysisGenerator(
        buildSemanticParser(), buildHeuristics(), buildCandidateEngine(), entityResolver);
  }

  private static MultiCandidateInterpretationEngine buildCandidateEngine() {
    return new MultiCandidateInterpretationEngine(
        buildSemanticParser(), buildHeuristics(),
        new com.example.BACKEND.catalogue.decision.clarification.AmbiguityDetector(
            new com.example.BACKEND.catalogue.decision.clarification.DomainOntology(
                new DomainAnalyticalDefaults())),
        new AnalyticalIntentClassifier());
  }

  private static SemanticAnalyticalParser buildSemanticParser() {
    var er = new QueryEntityResolver(new SemanticDictionary());
    return new SemanticAnalyticalParser(er,
        new ContributionQuestionParser(er), new DimensionImpactParser(er));
  }

  private static FallbackAnalyticalHeuristics buildHeuristics() {
    return new FallbackAnalyticalHeuristics(new SemanticFallbackDictionary(),
        new QueryEntityResolver(new SemanticDictionary()));
  }

  private static CandidateExecutionOrchestrator buildCandidateOrchestrator() {
    var labels = new PresentationLabelResolver();
    var executor = new CandidateMaterializationExecutor(
        new SchemaProfiler(),
        new GroupByExecutor(),
        new NumericDimensionBucketer(new MetricBucketingEngine()),
        labels);
    return new CandidateExecutionOrchestrator(executor, new CandidateResultScorer());
  }

  private static AnalyticalPlanningEngine buildPlanningEngine() {
    return new AnalyticalPlanningEngine(
        new AnalyticalIntentClassifier(),
        new QueryDecompositionEngine(new DomainAnalyticalDefaults(), new MetricSemanticRegistry(new DomainAnalyticalDefaults())),
        new MetricRequirementResolver(),
        new ComparativeFrameworkBuilder(),
        new InvestigationPlanner());
  }
}
