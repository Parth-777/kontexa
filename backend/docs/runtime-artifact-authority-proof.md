# Runtime Artifact Authority — Code Proof

Proof derived only from `DecisionRuntime.execute()` and its direct callees.  
No architectural interpretation beyond what the call sites show.

The runtime does **not** have a single authoritative object for SQL, materialization, or the final response.

---

## Executive summary

### Per-stage: created / consumed / ignored

| Stage | Created | Consumed | Ignored |
|-------|---------|----------|---------|
| **2b** | `SemanticResolution` (6 fields) | See field table in § Stage 2b | `investigation()` → logs/facts only; `analysisPlan()` → SQL only; local `tableRef` L392–396 never read |
| **3** | `InvestigationPlan` | depth, IDCF, findings, governance, reasoning, synthesis, assembler | `AnalysisPlan` |
| **3.5** | `analyticalSpecs`, `analyticalCandidates` | specs → warehouse; candidates → orchestrator/verification | candidates **not** passed to SQL planner |
| **4** | `metricSpecs` | `warehouseExecutor` | `AnalysisPlan`, `InvestigationPlan`, `ResolvedAnalyticalQuestion` |
| **5** | `ComputationResultSet` | merged rows for all compute | — |
| **5a** | `candidateSelection` | override `materializedResult` if winner | discarded if `!hasWinner()` |
| **6** | `ExecutionFindings` | findings, governance, verification, synthesis, assembler | `AnalysisPlan`, `QuestionInvestigation` |

### 1. SQL generation — two objects, not one

| # | Object | Role |
|---|--------|------|
| **1a** | `AnalysisPlan` | Sole input to `deterministicPlanner.plan` (template SQL) |
| **1b** | `IntentResolution` + `RegistryResolutionBundle` | Input to `MetricPackPlanner.plan` (metric-pack SQL) |

**Precedence:** template batch executes first (`analyticalSpecs`), then metric pack (`metricSpecs`); rows merged into one `ComputationResultSet`.

**Within the template branch only:** `AnalysisPlan` is authoritative.

**Ignored for SQL:** `InvestigationPlan`, `ResolvedAnalyticalQuestion`, `QuestionInvestigation`, `AnalyticalCandidate` list.

### 2. Materialization — two objects with override precedence

| Precedence | Object | Role |
|------------|--------|------|
| **1 (default)** | `InvestigationPlan` | `IntentDrivenComputationFramework` → `AnalyticalQueryMaterializer.materialize(rows, profile, plan)` |
| **2 (override)** | `CandidateExecutionOrchestrator.SelectionResult` | `executionFindings.withMaterializedResult(winner)` when `hasWinner()` |

**Findings gate:** `StructuredFindingsEngine` uses only `executionFindings.materializedResult()`.

**`AnalysisPlan` is ignored for materialization** — never passed to `executionEngine.execute`.

### 3. Final response — split across `AnalyticalResponse` + `InsightOutput`

| Surface | Authoritative source |
|---------|---------------------|
| `executive_card`, `title`, `key_takeaway`, `chart_spec`, `narrative` | `AnalyticalResponse.executiveCard` (`DecisionResponseMapper` L50–72) |
| `insightId`, prescriptive fields (`actions`, `strategicImplications`, …) | `InsightOutput` (gated L98–107) |

**Assembler precedence (factual summary):** `ReasoningResult` prioritized findings → provisional builder (`resolvedQuestion` labels) → `ExecutivePresentationLayer` → `verificationOrchestrator.guardNarrative`.

### Bottom line

| Concern | Single object? | Authoritative object(s) | Precedence |
|---------|----------------|-------------------------|------------|
| **Template SQL** | **Yes** | `AnalysisPlan` | Only arg to `deterministicPlanner.plan` |
| **All warehouse SQL** | **No** | `AnalysisPlan` + `MetricPackPlanner` | Template batch, then metric pack; rows merged |
| **Materialization** | **No** | `InvestigationPlan` → `MaterializedQueryResult`; optional candidate override | IDCF first; `withMaterializedResult(winner)` if `hasWinner()` |
| **Findings content** | **Yes** | `ExecutionFindings.materializedResult()` | After override at L364–366 |
| **HTTP executive UI** | **No** | `AnalyticalResponse.executiveCard` primary; `InsightOutput` for prescriptive extras | Mapper L50–72 vs L102–107 |

**Critical split:** SQL is driven by `AnalysisPlan`; materialization grouping is driven by `InvestigationPlan` (from `ResolvedAnalyticalQuestion`). Different objects, different planners, both created every request in Stage 2b.

---

## Method under analysis

```191:576:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
public DecisionRunResult execute(DecisionExecutionContext ctx) {
    // ... full pipeline ...
    return new DecisionRunResult(output, analytical, verificationCtx);
}
```

API egress:

```70:71:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\api\DecisionController.java
DecisionRunResult result = runtime.execute(ctx);
return ResponseEntity.ok(responseMapper.toRunResponse(ctx.runId(), result, meta));
```

---

## Stage-by-stage: created / consumed / ignored

### Stage 0 — Lifecycle

| | |
|---|---|
| **Created** | `ExecutionRun` via `lifecycleManager.start(ctx)` |
| **Consumed** | `lifecycleManager.transition`, `lifecycleManager.complete/fail` |
| **Ignored** | — |

---

### Stage 1 — Intent

| | |
|---|---|
| **Created** | `IntentResolution intent` (L202), `Playbook playbook` (L203) |
| **Consumed** | `registryResolver.resolve(intent)` (L210); `playbookRouter.route(intent)` (L203); passed to synthesis, calibration, assembler |
| **Ignored** | — |

---

### Stage 2 — Registry

| | |
|---|---|
| **Created** | `RegistryResolutionBundle bundle` (L210) |
| **Consumed** | `questionResolver.resolveFull(intent, bundle)` (L215); `candidateGenerator.generate(..., bundle, ...)` (L251–252); `deterministicPlanner.plan(..., bundle)` (L283–284); `metricPackPlanner.plan(intent, bundle)` (L301); `evidenceAssembler.assemble(results, bundle)` (L422) |
| **Ignored** | — |

---

### Stage 2b — Semantic resolution

**Created** inside `questionResolver.resolveFull`:

```60:76:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\clarification\AnalyticalQuestionResolver.java
public SemanticResolution resolveFull(IntentResolution intent, RegistryResolutionBundle bundle) {
    var investigation = investigationPlanner.plan(intent.question(), bundle);
    QuestionSemantics semantics = semanticExtractor.extract(intent.question(), bundle);
    // ...
    AnalysisPlan analysisPlan = universalAnalysisPlanner.plan(
            intent.question(), bundle, investigation, resolution, transformSteps);
    ResolvedAnalyticalQuestion resolved = exploratoryPlanner.plan(
            intent, bundle, semantics, resolution, plan);
    return new SemanticResolution(resolved, semantics, resolution, plan, investigation, analysisPlan);
}
```

`SemanticResolution` fields and their fate in `DecisionRuntime`:

| Field | Created by | Consumed in `execute()` | Ignored in `execute()` |
|-------|------------|-------------------------|------------------------|
| `resolved()` → `ResolvedAnalyticalQuestion` | `exploratoryPlanner.plan` | L216–231 trace/logging; L239–240 `planningEngine.plan`; L339–343 candidate winner mutation; L465 verification; L508 `executionMode`; L565 assembler | — |
| `reasoningPlan()` → `QuestionDrivenReasoningPlan` | `reasoningPlanner.plan` | L217–220 trace; L239–240 embedded in `InvestigationPlan`; L397–399 `questionResultValidator` | — |
| `semantics()` → `QuestionSemantics` | `semanticExtractor` | L251–252 passed to `candidateGenerator` | L397–398 validator only |
| `metricResolution()` → `MetricResolution` | `metricResolutionEngine` | L251–252 candidates; L291–296,384–389 checkpoints; L337 scorer; L476–477 warehouse facts | — |
| `investigation()` → `QuestionInvestigation` | `investigationPlanner.plan` | **Logging only** L254–271; `buildWarehouseFacts` L656–657; `logPipelineCheckpoint` L842–851 | **Not passed to SQL planner, not passed to `executionEngine`, not passed to assembler** |
| `analysisPlan()` → `AnalysisPlan` | `universalAnalysisPlanner.plan` | L274–281 logging; L283–284 **sole input to template SQL planner**; L392–396 local `tableRef` assignment | **`tableRef` local (L392–396) is never read after assignment** |

**Proof `QuestionInvestigation` is not authoritative at runtime:** the variable `investigation` at L253 is never an argument to `deterministicPlanner`, `executionEngine`, or `planningEngine`. It was already consumed earlier inside `universalAnalysisPlanner.plan(..., investigation, ...)` when building `AnalysisPlan`.

---

### Stage 3 — Investigation planning

| | |
|---|---|
| **Created** | `InvestigationPlan investigationPlan` (L239–240) |

```239:240:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
InvestigationPlan investigationPlan = planningEngine.plan(
        intent, resolvedQuestion, reasoningPlan);
```

**`AnalyticalPlanningEngine` inputs (proves plan is derived from `ResolvedAnalyticalQuestion`, not `AnalysisPlan`):**

```44:50:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\planning\AnalyticalPlanningEngine.java
AnalyticalIntentType intentType = resolved != null && resolved.assumption() != null
        ? resolved.assumption().intent().canonical()
        : intentClassifier.classify(intent).canonical();

AnalyticalReasoningPlan reasoningPlan = resolved != null && resolved.assumption() != null
        ? decompositionEngine.decomposeFromResolution(intent, resolved)
        : decompositionEngine.decompose(intent, intentType);
```

| | |
|---|---|
| **Consumed** | L355 `depthEngine.analyse(results, investigationPlan)`; L363 `executionEngine.execute(results, investigationPlan)`; L409 `findingsEngine.produce(executionFindings, investigationPlan)`; L428 `coverageChecker.check`; L484,493,508 governance/reasoning; L527 synthesis; L565 assembler |
| **Ignored** | `AnalysisPlan` is **not** an input to `AnalyticalPlanningEngine` |

---

### Stage 3.5 — SQL specs + candidates

| | |
|---|---|
| **Created** | `List<AnalyticalCandidate> analyticalCandidates` (L251–252); `List<QuerySpec> analyticalSpecs` (L283–284) |

**Template SQL — only `AnalysisPlan` is passed:**

```283:284:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
List<QuerySpec> analyticalSpecs = deterministicPlanner.plan(
        semanticResolution.analysisPlan(), bundle);
```

```27:37:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\sqltemplates\DeterministicAnalyticalQueryPlanner.java
public List<QuerySpec> plan(AnalysisPlan analysisPlan, RegistryResolutionBundle bundle) {
    if (analysisPlan == null) { return List.of(); }
    if (!analysisPlan.executable()) { return List.of(); }
    List<QuerySpec> specs = analysisPlanSqlGenerator.generateAll(analysisPlan, bundle);
```

| | |
|---|---|
| **Consumed (SQL)** | `analyticalSpecs` → L312 `sqlExecutionService.executeTemplateBatch` |
| **Consumed (candidates)** | `analyticalCandidates` → L335–337 orchestrator; L464–465 verification |
| **Ignored (SQL)** | `analyticalCandidates` are **not** arguments to `deterministicPlanner` or `sqlExecutionService` |
| **Ignored (candidates)** | `InvestigationPlan`, `AnalysisPlan` not passed to `candidateGenerator` |

---

### Stage 4 — Metric pack SQL

| | |
|---|---|
| **Created** | `MetricPackExecutionPlan metricPlan`, `List<QuerySpec> metricSpecs` (L301–302) |
| **Consumed** | L315–320 `warehouseExecutor.execute(metricOnlyPlan, ...)` |
| **Ignored** | `AnalysisPlan`, `InvestigationPlan`, `ResolvedAnalyticalQuestion` — not inputs to `metricPackPlanner.plan(intent, bundle)` (L301) |

---

### Stage 5 — Warehouse merge

| | |
|---|---|
| **Created** | `templateResults` (L312), `metricResults` (L318–320), `ComputationResultSet results` (L321–324) |
| **Consumed** | All downstream compute/presentation stages receive merged `results` |
| **Ignored** | — |

```321:324:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
List<QueryResult> allResults = new java.util.ArrayList<>(templateResults);
allResults.addAll(metricResults.results());
ComputationResultSet results = new ComputationResultSet(
        runId, allResults, metricResults.executionMeta());
```

---

### Stage 5a — Candidate materialization override

| | |
|---|---|
| **Created** | `CandidateExecutionOrchestrator.SelectionResult candidateSelection` (L335–337) |
| **Consumed** | L364–366 may replace `executionFindings.materializedResult`; L339–343 mutates `resolvedQuestion` |
| **Ignored** | If `!candidateSelection.hasWinner()`, orchestrator output discarded |

**Override call site (precedence over default materialization):**

```363:366:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
ExecutionFindings executionFindings = executionEngine.execute(results, investigationPlan);
if (candidateSelection.hasWinner()) {
    executionFindings = executionFindings.withMaterializedResult(
            candidateSelection.winningMaterialization());
}
```

```112:114:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\ExecutionFindings.java
public ExecutionFindings withMaterializedResult(MaterializedQueryResult mat) {
    return new ExecutionFindings(
            entities, primaryRanking, efficiencyRanking, statisticalContext, findings, mat);
}
```

**Proof:** `withMaterializedResult` replaces **only** the `materializedResult` field; entities/rankings/findings from `IntentDrivenComputationFramework` are unchanged.

---

### Stage 5b–6 — Depth + dynamic execution

| | |
|---|---|
| **Created** | `AnalyticalDepthResult depthResult` (L355); `ExecutionFindings executionFindings` (L363, possibly L365–366) |
| **Consumed** | `depthResult` → reasoning L493, assembler L565; `executionFindings` → findings L409, governance L484, verification L463, synthesis L527, assembler L565 |
| **Ignored** | `AnalysisPlan`; `QuestionInvestigation` |

**Default materialization call site:**

```78:84:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\framework\IntentDrivenComputationFramework.java
public ExecutionFindings execute(ComputationResultSet resultSet, InvestigationPlan plan) {
    // ...
    AnalyticalIntentType intentType = plan != null
            ? plan.intentType()
            : AnalyticalIntentType.GENERAL_ANALYSIS;
```

```123:124:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\framework\IntentDrivenComputationFramework.java
MaterializedQueryResult materializedResult =
        materializer.materialize(allRows, profile, plan);
```

**`AnalyticalQueryMaterializer` accepts `InvestigationPlan`, not `AnalysisPlan`:**

```67:71:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\materialization\AnalyticalQueryMaterializer.java
public MaterializedQueryResult materialize(
        List<Map<String, Object>> rows,
        SchemaProfile             profile,
        InvestigationPlan         plan
) {
```

**Grouping column inside materializer comes from `InvestigationPlan.reasoningPlan()`:**

```59:75:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\materialization\MaterializationPlanBuilder.java
if (plan != null && plan.reasoningPlan() != null
        && plan.reasoningPlan().metricBinding() != null) {
    String groupingCol = plan.reasoningPlan().metricBinding().groupingColumn();
    if (groupingCol != null && !groupingCol.isBlank()) {
        specs.add(new MaterializationSpec(
                groupingCol, groupingCol, /* ... */, -1));
    }
}
```

`InvestigationPlan.reasoningPlan()` is `AnalyticalReasoningPlan` built from `ResolvedAnalyticalQuestion` via `decomposeFromResolution` (see Stage 3).

---

### Stage 6b — Structured findings

| | |
|---|---|
| **Created** | `StructuredFindingsBundle findingsBundle` (L408–409) |
| **Consumed** | Grounding L455–456; governance L483–485; reasoning L492–493; synthesis L527; assembler L561 |
| **Ignored** | `AnalysisPlan` |

**Findings require `executionFindings.materializedResult()`:**

```63:74:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\findings\StructuredFindingsEngine.java
public StructuredFindingsBundle produce(ExecutionFindings executionFindings,
                                        InvestigationPlan plan) {
    if (executionFindings == null || !executionFindings.hasContent()) {
        return StructuredFindingsBundle.empty();
    }
    MaterializedQueryResult matResult = executionFindings.materializedResult();
    if (matResult == null || !matResult.hasContent()) {
        return StructuredFindingsBundle.empty();
    }
```

`InvestigationPlan plan` is passed but routing uses `plan.intentType()` only after materialized content exists.

---

### Stages 7–11 — Evidence, synthesis, presentation

| Created | Consumed | Ignored |
|---------|----------|---------|
| `evidence`, `coverageReport`, `ranked`, `constitution`, `calibration` | Synthesis L525–528 when `hasExecutableData` | — |
| `groundedBundle` / `synthesisBundle` (governed) | Reasoning L492–493; synthesis L527 | — |
| `ReasoningResult reasoningResult` | Governance L504–508; assembler L563 | — |
| `InsightOutput output` | `DecisionRunResult` L576; assembler L561 (`insight` param) | `InsightOutput` narrative **not** used for API `title`/`key_takeaway` (see Final response) |
| `AnalyticalResponse analytical` | `DecisionRunResult` L576; `DecisionResponseMapper` L47+ | — |

**Synthesis gate:**

```520:528:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
boolean hasExecutableData = synthesisBundle.hasStructuredFindings()
        || (executionFindings != null && executionFindings.materializedResult() != null
                && executionFindings.materializedResult().hasContent())
        || results.results().stream().anyMatch(r -> r.rows() != null && !r.rows().isEmpty());
if (hasExecutableData) {
    output = synthesisService.synthesise(
            ranked, evidence, intent, playbook, constitution, calibration,
            investigationPlan, coverageReport, depthResult, executionFindings,
            synthesisBundle);
```

---

## Final answers: authoritative objects

### 1. SQL generation

**There is not one object.** Two independent planners produce `QuerySpec` lists; both execute.

| Order | Object | Call site | Planner |
|-------|--------|-----------|---------|
| **1a** | `AnalysisPlan` | `DecisionRuntime` L283–284 | `DeterministicAnalyticalQueryPlanner.plan(analysisPlan, bundle)` |
| **1b** | `IntentResolution` + `RegistryResolutionBundle` | `DecisionRuntime` L301 | `MetricPackPlanner.plan(intent, bundle)` |

**Execution order:**

```312:320:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
List<QueryResult> templateResults = sqlExecutionService.executeTemplateBatch(
        analyticalSpecs, intent.question(), ctx.tenantId(), runId);
// ...
ComputationResultSet metricResults = metricSpecs.isEmpty()
        ? new ComputationResultSet(runId, List.of(), Map.of())
        : warehouseExecutor.execute(metricOnlyPlan, ctx.tenantId());
```

**Within the template branch only**, authority is a single object:

```27:37:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\execution\sqltemplates\DeterministicAnalyticalQueryPlanner.java
public List<QuerySpec> plan(AnalysisPlan analysisPlan, RegistryResolutionBundle bundle) {
```

**Ignored for SQL:** `InvestigationPlan`, `ResolvedAnalyticalQuestion`, `QuestionInvestigation`, `List<AnalyticalCandidate>`, `QuestionDrivenReasoningPlan` (except trace).

**Secondary authority inside template execution:** `SqlFallbackExecutionChain` may rewrite SQL strings at execution time (`AnalyticalSqlExecutionService.executeWithFallbacks`, L52).

---

### 2. Materialization

**There is not one object.** Precedence:

| Precedence | Object | Call site | Effect |
|------------|--------|-----------|--------|
| **1 (default)** | `InvestigationPlan` | `DecisionRuntime` L363 → `IntentDrivenComputationFramework.execute(results, investigationPlan)` L78–84 → `materializer.materialize(allRows, profile, plan)` L123–124 | Produces `ExecutionFindings.materializedResult` |
| **2 (override)** | `CandidateExecutionOrchestrator.SelectionResult` | `DecisionRuntime` L364–366 `withMaterializedResult(candidateSelection.winningMaterialization())` | Replaces `materializedResult` if `hasWinner()` |

```37:40:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\candidate\CandidateExecutionOrchestrator.java
public boolean hasWinner() {
    return winner != null && winner.totalScore() > 0
            && winner.result() != null && winner.result().hasContent();
}
```

**Warehouse rows fed to materialization** come from `ComputationResultSet results` (merged template + metric-pack query results), not from `AnalysisPlan` or `InvestigationPlan` directly.

**`InvestigationPlan` provenance chain (materialization grouping):**

```
ResolvedAnalyticalQuestion (exploratoryPlanner)
  → AnalyticalPlanningEngine.plan(intent, resolvedQuestion, reasoningPlan)  [DecisionRuntime L239-240]
  → InvestigationPlan.reasoningPlan() = AnalyticalReasoningPlan
  → MaterializationPlanBuilder.build(plan, ...)  [groupingColumn from metricBinding]
```

**`AnalysisPlan` is not passed to `IntentDrivenComputationFramework` or `AnalyticalQueryMaterializer`.**

**Downstream authority for findings:** `ExecutionFindings.materializedResult()` (after optional candidate override) is the only input `StructuredFindingsEngine` uses for content (L70–74).

---

### 3. Final response

**There is not one object.** HTTP response is assembled from `DecisionRunResult` containing two top-level artifacts:

```245:248:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\contracts\DecisionModels.java
public record DecisionRunResult(
        InsightOutput insight,
        com.example.BACKEND.catalogue.decision.presentation.AnalyticalResponse analytical,
        com.example.BACKEND.catalogue.decision.verification.AnalyticalVerificationOrchestrator.VerificationContext verification
```

**`DecisionResponseMapper` proves split authority:**

| JSON field | Source object | Code |
|------------|---------------|------|
| `executive_card`, `title`, `key_takeaway`, `chart_spec`, `executive_summary`, `narrative` | `AnalyticalResponse.executiveCard()` | L50–52, L60–72, L108 |
| `insightId` | `InsightOutput.insightId()` | L57 |
| `actions`, `strategicImplications`, `operationalRisks`, `businessCauses` | `InsightOutput` (if prescriptive gate passes) | L102–107 |
| `findings`, `confidence`, `response_mode`, `table_spec` | `AnalyticalResponse` | L79–86, debug L128+ |

**`AnalyticalResponse` assembly call site:**

```561:566:C:\kontexa\backend\src\main\java\com\example\BACKEND\catalogue\decision\runtime\DecisionRuntime.java
AnalyticalResponse analytical = responseAssembler.assemble(
        synthesisBundle, depthResult, output, constitution, coverageReport,
        intent, ranked, evidence, governedPresentation.reasoning(),
        assemblyConfidence,
        investigationPlan, executionFindings, resolvedQuestion, results,
        evidenceAssessment, verificationCtx, trace);
```

**Precedence inside `AnalyticalResponseAssembler.assemble` (factual summary / primary content):**

| Step | Source | Lines |
|------|--------|-------|
| 1 | `ReasoningResult.prioritizedFindings().getFirst()` → `buildFactualSummary` | L277–279, L291 |
| 2 | `narrativeTemplates.support(primary.finding())` appended | L292–296 |
| 3 | If findings empty: `provisionalBuilder.build(executionFindings, metricLabel, grouping)` where labels from `resolvedQuestion.assumption()` | L315–325 |
| 4 | If still blank: `findings.getFirst().summary()` | L333–335 |
| 5 | `ExecutivePresentationLayer.present(...)` → `executiveCard` | L352–355 |
| 6 | If blank: `executiveCard.keyTakeaway()` / `executiveSummary()` | L364–368 |
| 7 | `verificationOrchestrator.guardNarrative(factualSummary, verificationContext)` | L371–374 |

**Chart precedence:**

| Step | Source | Lines |
|------|--------|-------|
| 1 | `primary.chartSpec()` from `ReasoningResult` | L285–286 |
| 2 | Else `reasoningResult.primaryChart()` | L287–288 |
| 3 | Else `visualizationPlanner.plan(findingsBundle, depthResult)` | L289 |
| 4 | Else `executiveCard.visualization()` | L361–362 |
| 5 | Correlation path: `executionFindings.materializedResult().correlation()` | L408–418 |

**`InsightOutput` in assembler:** passed to `groundingService.ground(findingsBundle, insight, evidence, constitution, coverage)` (L267–268) only. It is **not** the source of `executive_card` fields in the API mapper.

**Objects that influence final UI but do not singularly determine it:**

| Object | Role in final response |
|--------|------------------------|
| `StructuredFindingsBundle synthesisBundle` | Input to reasoning → prioritized findings |
| `ReasoningResult` (from `governedPresentation.reasoning()`) | Primary findings list + chart |
| `ExecutionFindings` | Provisional fallback, correlation payload, table spec |
| `ResolvedAnalyticalQuestion` | Labels for provisional builder; confidence penalty L273–275; assembler metadata L342–347 |
| `InvestigationPlan` | `ExecutivePresentationLayer`, response mode L384–388 |
| `InsightOutput` | LLM prescriptive fields only (gated L98–100) |

---

## Summary table

| Concern | Single authoritative object? | Proof |
|---------|---------------------------|-------|
| **Template SQL** | **Yes: `AnalysisPlan`** | Only argument to `deterministicPlanner.plan` (L283–284, `DeterministicAnalyticalQueryPlanner` L27) |
| **All warehouse SQL** | **No** | `AnalysisPlan` specs + `MetricPackPlanner` specs both execute (L312, L318–320) |
| **Materialized evidence** | **No** | Default: `InvestigationPlan` → IDCF; override: `CandidateExecutionOrchestrator` winner (L363–366) |
| **HTTP executive UI** | **No** | `AnalyticalResponse.executiveCard` primary (`DecisionResponseMapper` L50–72); `InsightOutput` for prescriptive extras only |
| **Structured findings content** | **Yes: `ExecutionFindings.materializedResult()`** | `StructuredFindingsEngine` L70–74 returns empty without it |

---

## Objects explicitly ignored on the hot path

| Object | Evidence |
|--------|----------|
| `QuestionInvestigation` (field on `SemanticResolution`) | Only logging L254–271 + warehouse facts; never passed to planners/execution/assembler |
| `AnalysisPlan` (for materialization/planning/presentation) | Only used for template SQL L283–284; not passed to `planningEngine`, `executionEngine`, `findingsEngine` materialization path |
| `List<AnalyticalCandidate>` (for SQL) | Never passed to `deterministicPlanner` or `sqlExecutionService` |
| `QuestionDrivenReasoningPlan` (standalone) | Embedded into `InvestigationPlan.questionDrivenPlan()` L86; not used separately except trace L217–220 |
| `RecoveryResponseBuilder` | Injected in `DecisionRuntime` L107, **never called** in `execute()` |
| Local `tableRef` L392–396 | Assigned, never read |

---

## Related documents

- [runtime-architecture-investigation.md](./runtime-architecture-investigation.md)
- [architecture-b-deletion-plan.md](./architecture-b-deletion-plan.md)
