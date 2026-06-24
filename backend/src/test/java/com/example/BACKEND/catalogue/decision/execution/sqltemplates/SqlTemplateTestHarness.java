package com.example.BACKEND.catalogue.decision.execution.sqltemplates;



import com.example.BACKEND.catalogue.decision.planning.AnalysisPlanSqlGenerator;

import com.example.BACKEND.catalogue.decision.transforms.BigQueryDialect;

import com.example.BACKEND.catalogue.decision.transforms.BucketizationEngine;

import com.example.BACKEND.catalogue.decision.transforms.DerivedDimensionRegistry;

import com.example.BACKEND.catalogue.decision.transforms.SchemaColumnDetector;

import com.example.BACKEND.catalogue.decision.transforms.SemanticQueryRewriter;

import com.example.BACKEND.catalogue.decision.transforms.SemanticTransformationEngine;

import com.example.BACKEND.catalogue.decision.transforms.TemporalDerivationEngine;



/**

 * Wires SQL template stack for unit tests.

 */

public final class SqlTemplateTestHarness {



    final AnalyticalSqlTemplateEngine templateEngine;

    final SemanticTransformationEngine transformationEngine;

    final AnalysisPlanSqlGenerator analysisPlanSqlGenerator;

    public final DeterministicAnalyticalQueryPlanner planner;

    final SqlFallbackExecutionChain fallbackChain;

    final DatasetProfileRegistry profiles;



    private SqlTemplateTestHarness(

            AnalyticalSqlTemplateEngine templateEngine,

            SemanticTransformationEngine transformationEngine,

            AnalysisPlanSqlGenerator analysisPlanSqlGenerator,

            DeterministicAnalyticalQueryPlanner planner,

            SqlFallbackExecutionChain fallbackChain,

            DatasetProfileRegistry profiles

    ) {

        this.templateEngine = templateEngine;

        this.transformationEngine = transformationEngine;

        this.analysisPlanSqlGenerator = analysisPlanSqlGenerator;

        this.planner = planner;

        this.fallbackChain = fallbackChain;

        this.profiles = profiles;

    }



    public static SqlTemplateTestHarness create() {

        IntentAggregationStrategy aggregationStrategy = new IntentAggregationStrategy();

        GroupedMetricSqlBuilder sqlBuilder = new GroupedMetricSqlBuilder(aggregationStrategy);

        AnalyticalSqlTemplateEngine templateEngine = new AnalyticalSqlTemplateEngine(

                aggregationStrategy,

                new ContributionSqlTemplate(sqlBuilder),

                new TrendSqlTemplate(sqlBuilder),

                new RankingSqlTemplate(sqlBuilder),

                new ComparisonSqlTemplate(sqlBuilder),

                new DistributionSqlTemplate(sqlBuilder),

                new EfficiencySqlTemplate(sqlBuilder),

                new RelationshipSqlTemplate());

        DatasetProfileRegistry profiles = new DatasetProfileRegistry();

        SemanticTransformationEngine transformationEngine = new SemanticTransformationEngine(

                new DerivedDimensionRegistry(),

                new SchemaColumnDetector(),

                new TemporalDerivationEngine(new BigQueryDialect()),

                new BucketizationEngine(),

                profiles);

        AnalysisPlanSqlGenerator analysisPlanSqlGenerator = new AnalysisPlanSqlGenerator(

                templateEngine, transformationEngine);

        DeterministicAnalyticalQueryPlanner planner = new DeterministicAnalyticalQueryPlanner(

                analysisPlanSqlGenerator);

        SqlFallbackExecutionChain fallbackChain = new SqlFallbackExecutionChain(

                transformationEngine, aggregationStrategy);

        return new SqlTemplateTestHarness(

                templateEngine, transformationEngine, analysisPlanSqlGenerator,

                planner, fallbackChain, profiles);

    }

}

