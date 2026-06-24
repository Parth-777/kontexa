package com.example.BACKEND.catalogue.decision.planning;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DimensionDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.EntityDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.MetricDescriptor;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.DeterministicAnalyticalQueryPlanner;
import com.example.BACKEND.catalogue.decision.execution.sqltemplates.SqlTemplateTestHarness;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigation;
import com.example.BACKEND.catalogue.decision.investigation.QuestionInvestigationPlanner;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolution;
import com.example.BACKEND.catalogue.decision.semantics.MetricResolutionEngine;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemanticExtractor;
import com.example.BACKEND.catalogue.decision.semantics.QuestionSemantics;
import com.example.BACKEND.catalogue.decision.semantics.catalog.MetricResolutionTestSupport;
import com.example.BACKEND.catalogue.decision.semantics.catalog.UniversalPlannerTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Traces execution for an unseen student dataset — no student-specific code.
 */
class StudentDatasetExecutionTraceTest {

    private static final String QUESTION = "Which subjects have the highest exam performance?";
    private static final String TABLE = "student_records";

    @Test
    void studentRankingQuestion_producesExecutablePlanAndSql() {
        RegistryResolutionBundle bundle = studentBundle();
        QuestionSemanticExtractor extractor = MetricResolutionTestSupport.extractor();
        MetricResolutionEngine metricEngine = MetricResolutionTestSupport.engine();
        QuestionInvestigationPlanner investigationPlanner = UniversalPlannerTestSupport.investigationPlanner();
        UniversalAnalysisPlanner planner = UniversalPlannerTestSupport.universalPlanner();
        DeterministicAnalyticalQueryPlanner sqlPlanner = SqlTemplateTestHarness.create().planner;

        QuestionSemantics semantics = extractor.extract(QUESTION, bundle);
        System.out.println("SEMANTICS metric=" + semantics.primaryMetric()
                + " dimension=" + semantics.dimension()
                + " intent=" + semantics.intent());

        MetricResolution resolution = metricEngine.resolve(semantics, bundle);
        System.out.println("METRIC_RESOLUTION metric=" + resolution.primaryMetric()
                + " dimension=" + resolution.dimension()
                + " usable=" + resolution.isUsable()
                + (resolution.rejected() ? " REJECTED:" + resolution.rejectionReason() : ""));

        QuestionInvestigation investigation = investigationPlanner.plan(QUESTION, bundle);
        System.out.println("INVESTIGATION metric=" + investigation.extraction().metricKey()
                + " dimension=" + investigation.dimension().columnKey()
                + " intent=" + investigation.extraction().intent()
                + " executable=" + investigation.executable());

        AnalysisPlan plan = planner.plan(QUESTION, bundle, investigation, resolution, List.of());
        System.out.println("ANALYSIS_PLAN intent=" + plan.intent()
                + " metric=" + plan.primaryMetric()
                + " dimension=" + plan.dimension()
                + " executable=" + plan.executable()
                + (plan.executable() ? "" : " blocked=" + plan.blockingReason()));

        assertTrue(plan.executable(), plan.blockingReason());
        assertEquals(AnalysisIntent.RANKING, plan.intent());
        assertEquals("exam_score", plan.primaryMetric());
        assertEquals("subject", plan.dimension());

        List<QuerySpec> specs = sqlPlanner.plan(plan, bundle);
        assertFalse(specs.isEmpty());
        String sql = specs.getFirst().sql();
        System.out.println("SQL:\n" + sql);
        assertTrue(sql.contains("exam_score"));
        assertTrue(sql.contains("subject"));
        assertTrue(sql.contains("GROUP BY"));
        assertTrue(sql.contains(TABLE));
    }

    private static RegistryResolutionBundle studentBundle() {
        return new RegistryResolutionBundle(
                List.of(new EntityDescriptor("students", TABLE, List.of("student_id"), List.of("education"))),
                List.of(
                        new MetricDescriptor(TABLE + ".exam_score", "exam_score", "FLOAT", "AVG", null),
                        new MetricDescriptor(TABLE + ".attendance_rate", "attendance_rate", "FLOAT", "AVG", null),
                        new MetricDescriptor(TABLE + ".graduation_rate", "graduation_rate", "FLOAT", "AVG", null),
                        new MetricDescriptor(TABLE + ".study_hours_per_week", "study_hours_per_week", "FLOAT", "AVG", null),
                        new MetricDescriptor(TABLE + ".teacher_experience_years", "teacher_experience_years", "FLOAT", "AVG", null),
                        new MetricDescriptor(TABLE + ".class_size", "class_size", "FLOAT", "AVG", null)
                ),
                List.of(
                        new DimensionDescriptor(TABLE + ".subject", "subject", "CATEGORICAL"),
                        new DimensionDescriptor(TABLE + ".school_name", "school_name", "CATEGORICAL"),
                        new DimensionDescriptor(TABLE + ".grade_level", "grade_level", "CATEGORICAL")
                ),
                null);
    }
}
