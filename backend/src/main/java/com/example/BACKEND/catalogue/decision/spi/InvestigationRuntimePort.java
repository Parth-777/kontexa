package com.example.BACKEND.catalogue.decision.spi;

import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.DecisionRunResult;
import com.example.BACKEND.catalogue.decision.contracts.DecisionModels.RegistryResolutionBundle;
import com.example.BACKEND.catalogue.decision.execution.trace.ExecutionTrace;
import com.example.BACKEND.catalogue.semantic.phase2.GptSemanticPlanningOrchestrator.GptPlanningOutcome;

/**
 * Phase 1-owned neutral integration seam (SPI) for delegating an INVESTIGATION-mode
 * request to a sibling investigation runtime.
 *
 * <h2>Ownership &amp; dependency direction</h2>
 * <ul>
 *   <li>This interface is <b>owned by Phase 1</b> and is expressed entirely in Phase 1
 *       types ({@link DecisionRunResult}, {@link RegistryResolutionBundle},
 *       {@link GptPlanningOutcome}, {@link ExecutionTrace}).</li>
 *   <li>It imports <b>nothing</b> from any Phase 2 ({@code catalogue.investigation})
 *       package, so it introduces no dependency on Phase 2.</li>
 *   <li>A future Phase 2 investigation runtime will <b>implement</b> this port. The
 *       dependency arrow therefore points one way only: Phase 2 &rarr; Phase 1.</li>
 *   <li>Phase 1 may later depend on this <b>abstraction</b> (never on a concrete Phase 2
 *       class), preserving the coexistence invariant.</li>
 * </ul>
 *
 * <h2>Step scope</h2>
 * This step adds the contract <b>only</b>. There is deliberately:
 * <ul>
 *   <li>no implementation,</li>
 *   <li>no Spring bean / component annotation,</li>
 *   <li>no routing branch, and</li>
 *   <li>no reference to this port from {@code DecisionRuntime} or any other Phase 1 class.</li>
 * </ul>
 * Consequently no runtime code path reaches an implementation of this interface yet.
 */
public interface InvestigationRuntimePort {

    /**
     * Execute an investigation for a planner-classified INVESTIGATION request and
     * return a standard {@link DecisionRunResult} so the existing response layer can
     * render it through the single chatbox.
     *
     * @param runId           the decision run identifier
     * @param question        the original user question
     * @param tenantId        the tenant / workspace identifier
     * @param planningOutcome the read-only planning outcome from the existing GPT planner
     * @param bundle          the resolved catalogue/registry bundle (canonical bundle type)
     * @param trace           the execution trace accumulated so far
     * @return a decision run result produced by the sibling investigation runtime
     */
    DecisionRunResult run(
            String runId,
            String question,
            String tenantId,
            GptPlanningOutcome planningOutcome,
            RegistryResolutionBundle bundle,
            ExecutionTrace trace
    );
}
