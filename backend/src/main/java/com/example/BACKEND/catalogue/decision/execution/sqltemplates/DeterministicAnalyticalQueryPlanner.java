package com.example.BACKEND.catalogue.decision.execution.sqltemplates;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.QuerySpec;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlan;
import com.example.BACKEND.catalogue.decision.planning.AnalysisPlanSqlGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates deterministic warehouse SQL exclusively from a schema-bound {@link AnalysisPlan}.
 */
@Component
public class DeterministicAnalyticalQueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(DeterministicAnalyticalQueryPlanner.class);

    private final AnalysisPlanSqlGenerator analysisPlanSqlGenerator;

    public DeterministicAnalyticalQueryPlanner(AnalysisPlanSqlGenerator analysisPlanSqlGenerator) {
        this.analysisPlanSqlGenerator = analysisPlanSqlGenerator;
    }

    public List<QuerySpec> plan(AnalysisPlan analysisPlan, RegistryResolutionBundle bundle) {
        if (analysisPlan == null) {
            log.warn("[sql-planner] null analysis plan");
            return List.of();
        }
        if (!analysisPlan.executable()) {
            log.warn("[sql-planner] blocked question={} reason={}",
                    analysisPlan.question(), analysisPlan.blockingReason());
            return List.of();
        }
        List<QuerySpec> specs = analysisPlanSqlGenerator.generateAll(analysisPlan, bundle);
        if (specs.isEmpty()) {
            log.warn("[sql-planner] no SQL for question={} intent={}",
                    analysisPlan.question(), analysisPlan.intent());
        }
        return specs;
    }
}
