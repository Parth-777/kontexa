# Analytical Truth Mutation Audit

Scope: the nine fields that define analytical truth — not visualization, response formatting, confidence, chart preferences, or narrative metadata.

## Analytical truth fields

| Field | Where first bound (schema path) |
|-------|----------------------------------|
| `intent` | `UniversalAnalysisPlanner.classifyIntent()` → `AnalysisPlan.intent` |
| `primaryMetric` | `MetricResolutionEngine` → `AnalysisPlan.primaryMetric` |
| `secondaryMetric` | `MetricResolutionEngine` / investigation share → `AnalysisPlan.secondaryMetric` |
| `aggregation` | **Not stored on `AnalysisPlan`** — first applied at SQL template time via `IntentAggregationStrategy`; on legacy path via `ExploratoryAnalysisPlanner` → `AnalyticalAssumption.aggregation` |
| `dimension` | `QuestionInvestigation` / `MetricResolution` → `AnalysisPlan.dimension` |
| `groupingAlias` | `QuestionInvestigation.dimension().groupingAlias()` or share path → `AnalysisPlan.groupingAlias` |
| `relationshipVariable` | `MetricResolutionEngine` → `AnalysisPlan.relationshipVariable` |
| `tableRef` | `RegistryResolutionBundle` → `AnalysisPlan.tableRef` |
| `executable` | `UniversalAnalysisPlanner.plan()` → `AnalysisPlan.executable` |

**Freeze point for SQL authority:** `UniversalAnalysisPlanner.plan()` returns `AnalysisPlan` — a record, **never mutated after creation**.

**Separate authority for materialization:** `InvestigationPlan.reasoningPlan().metricBinding()` built later from `ResolvedAnalyticalQuestion`, not from `AnalysisPlan`.

---

## Can truth fields change after first resolution?

**On `AnalysisPlan`:** No. All nine fields are set once; the record is immutable.

**In the runtime overall:** Yes. The same nine concepts are **re-bound, overridden, or re-inferred** in other objects and at later stages. That is the root cause.

---

## Mutations after first resolution (ordered by pipeline)

### 1. `AnalyticalPlanningEngine` → `QueryDecompositionEngine.decomposeFromResolution`

**When:** `DecisionRuntime` L239 — after `resolveFull`, before warehouse.  
**Object mutated:** `InvestigationPlan.reasoningPlan.metricBinding` (new object; drives materialization).

| Field | How it changes | Source |
|-------|----------------|--------|
| `intent` | Overwritten to `COMPOSITION` or `CONTRIBUTION` | `semantic.contributionPlan()` L90; `semantic.dimensionImpactPlan()` L101 |
| `intent` | Overwritten `RETENTION` → `DISTRIBUTION` | `decompose()` L42–44 (fallback path) |
| `primaryMetric` | Replaced with `cp.numeratorMetric()` or `dip.metricColumn()` | semantic parser branches L82–96 |
| `secondaryMetric` | Implicit via ratio binding denominator | `ContributionAnalysisPlan` L82–86 |
| `aggregation` | Set to `RATIO` or `SUM` | L84, L95 — not from resolution |
| `dimension` / `groupingAlias` | `dip.bucketStrategy()` or `inferDimension()` when assumption grouping blank | L96–97, L103–104 |
| `groupingAlias` | `inferDimension()` → domain defaults (`trip_distance_bucket`, `pickup_zone`, etc.) | L372–380 |

`AnalysisPlan` is unchanged. Materialization reads `InvestigationPlan`, not `AnalysisPlan`.

---

### 2. `MetricSemanticRegistry.bindForIntent` (called from decomposition)

**When:** Inside `QueryDecompositionEngine.decompose()` / binding construction.  
**Object:** `MetricDecompositionBinding`.

| Field | How it changes |
|-------|----------------|
| `primaryMetric` | Resolved via domain defaults → often `total_amount` / `domain.revenueColumn()` L65–66 |
| `aggregation` | Overridden by intent (`RATIO` for EFFICIENCY, `SUM` for CONTRIBUTION) L68–73 |
| `dimension` / `groupingAlias` | `groupCol` rewritten to `domain.distanceColumn()` when grouping label contains "distance" L76–77 |

---

### 3. `DecisionRuntime` — `ResolvedAnalyticalQuestion.withWinningCandidate`

**When:** L340 — **after warehouse**, after `InvestigationPlan` already built.  
**Object:** `ResolvedAnalyticalQuestion.assumption` (reassigned).

| Field | Source of new value |
|-------|---------------------|
| `primaryMetric` | `winner.primaryMetric()` L67 |
| `secondaryMetric` | `winner.secondaryMetric()` L69 |
| `dimension` / `groupingAlias` | `winner.grouping()` L70 |
| `aggregation` | `winner.aggregation()` L71 |
| `intent` | `winner.intent().canonical()` L72 |

`InvestigationPlan` and `AnalysisPlan` are **not updated**. Response/clarification path sees new truth; materialization already ran on old `InvestigationPlan`.

---

### 4. `CandidateExecutionOrchestrator` + `DecisionRuntime` L364–366

**When:** After warehouse, before findings.  
**Object:** `ExecutionFindings.materializedResult` replaced with winner's materialization.

| Field | Effect |
|-------|--------|
| `primaryMetric` | Winner candidate's metric used for in-memory GROUP BY |
| `dimension` / `groupingAlias` | Winner's `grouping()` / `bucketColumn()` |
| `aggregation` | Winner's `aggregation()` |
| `intent` | Winner's `intent()` |

SQL already ran from `AnalysisPlan`. Findings can reflect a **different** metric/dimension/grouping than SQL.

---

### 5. `AnalyticalSqlExecutionService` + `SqlFallbackExecutionChain`

**When:** Warehouse execution L312.  
**Object:** **Executed SQL** (not plan records).

| Field | How it changes |
|-------|----------------|
| `aggregation` | Fallback D: `SUM` → `AVG` L50–51, L115 (`SqlFallbackExecutionChain`) |
| `dimension` / `groupingAlias` | Fallbacks A–C: alternate bucket expressions / GROUP BY columns |
| `primaryMetric` | Unchanged in spec metadata; SQL SELECT may use different aggregate expression |

`AnalysisPlan` unchanged; **effective warehouse result** may not match plan.

---

### 6. `AnalyticalQueryMaterializer` — row-driven re-inference

**When:** `IntentDrivenComputationFramework` L124.  
**Object:** Effective truth inferred from warehouse rows, not from any frozen contract.

| Field | How it changes |
|-------|----------------|
| `primaryMetric` | `SchemaProfile.primaryValue()` — highest-magnitude numeric column in rows L120–121, not `AnalysisPlan.primaryMetric` |
| `dimension` / `groupingAlias` | `GroupedWarehouseResultDetector.detect(rows)` picks dimension column from row keys L100–103; `MaterializationPlanBuilder` adds `SchemaProfile.dimensions()` L94–108 |
| `aggregation` | `GroupByExecutor` always SUMs value column; entity framework AVG-aggregates rates |

`InvestigationPlan.metricBinding.groupingColumn` is priority spec L60–75, but **competes** with schema-discovered dimensions.

---

### 7. `CandidateMaterializationExecutor.resolveValueColumn`

**When:** Per-candidate scoring in `CandidateExecutionOrchestrator` L77.  
**Object:** Effective metric column for candidate materialization.

| Field | How it changes |
|-------|----------------|
| `primaryMetric` | Falls back to `profile.primaryValue()` L112–113, then hardcoded `total_amount` / `fare_amount` L119 |

---

## Mutations during resolution (before freeze, same request)

These happen inside `AnalyticalQuestionResolver.resolveFull` **before** `AnalysisPlan` exists, but **after** initial semantic extract — they change truth before the "first resolved" snapshot:

| Location | Fields changed | Mechanism |
|----------|----------------|-----------|
| `AnalyticalQuestionResolver.overlayInvestigationSemantics` L93–122 | `primaryMetric`, `dimension`, `intent` | Investigation overlays extractor output |
| `MetricResolutionEngine.resolve` L61–94 | `dimension` (null for relationship), `grouping` (`relationship` / `bucketize` / `composition`) | `bucketize()` appends `_bucket` L285–289 |
| `UniversalAnalysisPlanner.classifyIntent` L156–171 | `intent` | Relationship detector overrides investigation intent |
| `UniversalAnalysisPlanner` share block L94–98 | `dimension` → null, `groupingAlias` → `composition` | Contribution without dimension |
| `ExploratoryAnalysisPlanner.selectBestPlan` L232–244 | all assumption fields | May skip `planFromReasoning`; picks heuristic candidate |
| `ExploratoryAnalysisPlanner` hard fallback L76–81 | `primaryMetric` → `total_amount`, `intent` → `GENERAL_ANALYSIS` | When no plan selected |
| `ExploratoryAnalysisPlanner.planFromReasoning` L260–261 | `aggregation` → hardcoded `SUM` | Ignores registry metric aggregation (e.g. AVG) |
| `MetricFallbackHierarchy.resolve` L87 | `primaryMetric` | NYC hierarchy when preferred unavailable |
| `HybridExecutionPolicy.selectForExecution` L117–121 | `primaryMetric`, `dimension`, `grouping`, `aggregation`, `intent` | Swaps to heuristic candidate when `EXPLORATORY_HEURISTIC` |

---

## Parallel authority at freeze (not mutation, but immediate divergence)

At end of `resolveFull`, two frozen truths exist for the same question:

| Field | `AnalysisPlan` | `ResolvedAnalyticalQuestion.assumption` |
|-------|----------------|----------------------------------------|
| `dimension` | `investigation.dimension().columnKey()` (e.g. `subject`) | `resolution.grouping()` (e.g. `subject_bucket`) |
| `aggregation` | Not stored; SQL uses intent→SUM | `SUM` hardcoded in `planFromReasoning` |
| `intent` | `AnalysisIntent` (6 values) | `AnalyticalIntentType` from reasoning/candidates |
| `executable` | Schema blockers honored | `viable` always true in exploratory path |

SQL reads left column. Materialization reads right column (via `InvestigationPlan`).

---

## Field-by-field: can it change after first resolution?

| Truth field | Mutated after freeze? | Where |
|-------------|----------------------|-------|
| `intent` | **Yes** | `QueryDecompositionEngine` L90, L101; `withWinningCandidate` L72; candidate materialization winner |
| `primaryMetric` | **Yes** | `QueryDecompositionEngine` semantic branches; `MetricSemanticRegistry.bindForIntent`; `withWinningCandidate`; `CandidateMaterializationExecutor` fallbacks; `SchemaProfile.primaryValue()` |
| `secondaryMetric` | **Yes** | `QueryDecompositionEngine` composition branch; `withWinningCandidate` |
| `aggregation` | **Yes** | `QueryDecompositionEngine` L84, L95; `withWinningCandidate` L71; `SqlFallbackExecutionChain` SUM→AVG; `IntentAggregationStrategy` at SQL gen (parallel derivation, not stored) |
| `dimension` | **Yes** | `QueryDecompositionEngine.inferDimension`; `MetricSemanticRegistry.bindForIntent` L76–77; `withWinningCandidate`; `GroupedWarehouseResultDetector`; `MaterializationPlanBuilder` schema dims |
| `groupingAlias` | **Yes** | Same as dimension paths; `bucketize` at resolution; `NumericDimensionBucketer` adds `_bucket` columns |
| `relationshipVariable` | **Rarely after freeze** | Set at resolution; materializer reads `questionDrivenPlan.resolution()` L256–265 as fallback labels |
| `tableRef` | **No** | Fixed at `AnalysisPlan` creation |
| `executable` | **No** | Fixed at `AnalysisPlan` creation; pipeline continues materialization regardless |

---

## Summary

1. **`AnalysisPlan` truth is frozen at creation and never modified.**
2. **Materialization and findings do not consume `AnalysisPlan`** — they consume `InvestigationPlan.metricBinding`, which is **re-bound** in `QueryDecompositionEngine.decomposeFromResolution` immediately after resolution.
3. **After warehouse**, `withWinningCandidate` and candidate materialization override can change metric / dimension / grouping / aggregation / intent again without updating `AnalysisPlan` or `InvestigationPlan`.
4. **SQL execution** can change effective aggregation and grouping via fallback SQL.
5. **Materialization** re-infers metric and dimension columns from warehouse row shape via `SchemaProfiler` and `GroupedWarehouseResultDetector`.

Every mutation site above is a place where cross-dataset failures can occur: the warehouse answers one analysis; materialization and findings answer another.

---

## Key source files

| File | Role |
|------|------|
| `clarification/AnalyticalQuestionResolver.java` | Dual fork: `AnalysisPlan` + `ResolvedAnalyticalQuestion` |
| `planning/UniversalAnalysisPlanner.java` | SQL authority freeze point |
| `exploration/ExploratoryAnalysisPlanner.java` | Legacy truth binding + mutations |
| `planning/QueryDecompositionEngine.java` | Re-binds truth for materialization |
| `governance/MetricSemanticRegistry.java` | Domain-default metric/grouping/aggregation |
| `runtime/DecisionRuntime.java` | Orchestrator; candidate winner override |
| `execution/sqltemplates/SqlFallbackExecutionChain.java` | Post-plan SQL aggregation/dimension changes |
| `execution/materialization/AnalyticalQueryMaterializer.java` | Row-driven metric/dimension re-inference |
| `candidate/CandidateMaterializationExecutor.java` | Candidate metric column fallbacks |
