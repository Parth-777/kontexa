# Planner Architecture Diagnostic Report

**Scope:** Investigation only — no code changes, no new mappings.  
**Goal:** Explain why question-class fixes keep breaking the *next* question, and where the structural bottlenecks are.  
**Date:** June 2026

---

## Executive summary

The pipeline is not one planner — it is **six overlapping planners** (intent, semantics, investigation, reasoning, SQL templates, post-SQL materialization) that each assume a **single canonical shape**:

> **One metric + one dimension → GROUP BY dimension → aggregate metric**

That shape fits NYC taxi contribution/ranking questions well. It fails systematically for correlation, share-of-segment, trend-without-temporal-columns, and any dataset whose column names do not match phrase dictionaries or regex templates.

Recent fixes (schema catalog, pre-aggregated materializer passthrough, metric aliases) patch **individual failure points** without changing this core contract. That is why one question starts working while another regresses.

---

## 1. Current pipeline (Question → SQL)

```
User question
    │
    ▼
[1] IntentResolution          IntentResolver + PlaybookRouter
    │                         (keywords → AnalyticalIntentType: RANKING, CORRELATION, …)
    ▼
[2] RegistryResolution        RegistryResolver → RegistryResolutionBundle
    │                         (entities, metrics[], dimensions[] from tenant catalogue)
    ▼
[3] SemanticResolution        AnalyticalQuestionResolver.resolveFull()
    │
    ├─ [3a] QuestionInvestigationPlanner
    │         SemanticCatalogBuilder → SchemaDrivenQuestionResolver (regex + token match)
    │         QuestionSemanticExtractor (SemanticDictionary + catalog enrich)
    │         DimensionResolver (catalog match → registry column)
    │         → QuestionInvestigation (executable?, discovery debug)
    │
    ├─ [3b] MetricResolutionEngine
    │         → MetricResolution (primaryMetric, dimension, grouping, rejected?)
    │
    ├─ [3c] AnalyticalReasoningPlanner
    │         → QuestionDrivenReasoningPlan (reasoning steps + QueryPlanStep[])
    │
    └─ [3d] ExploratoryAnalysisPlanner
              → ResolvedAnalyticalQuestion (always produces *something*, even at 0.3 confidence)
    ▼
[4] InvestigationPlan         AnalyticalPlanningEngine
    │                         (intent type, depth, steps — often decoupled from SQL shape)
    ▼
[5] SQL generation            DeterministicAnalyticalQueryPlanner  ◄── HARD GATE
    │
    ├─ IF investigation.executable() == false → return [] (NO SQL)
    │
    ├─ IF MetricResolution.isUsable() → planFromReasoning()
    │     iterate QueryPlanStep → SemanticQueryRewriter / buildTransformedSpec
    │     requires non-null dimension per step
    │
    └─ ELSE legacy path
          AnalyticalSqlTemplateEngine.detectIntent() + detectDimension() + HardMetricMappings
          requires non-null dimension for primary spec
    ▼
[6] Template render             AnalyticalSqlTemplateEngine → *SqlTemplate → GroupedMetricSqlBuilder
    │                           (6 templates: CONTRIBUTION, RANKING, TREND, COMPARISON,
    │                            DISTRIBUTION, EFFICIENCY — all GROUP BY variants)
    ▼
[7] Warehouse execute           AnalyticalSqlExecutionService → BigQuery
    ▼
[8] Post-SQL computation        IntentDrivenComputationFramework
    │                           SchemaProfiler → EntityExpansion → AnalyticalQueryMaterializer
    ▼
[9] Validation / synthesis      ResultQualityValidator, verification, findings, narrative
```

**Key observation:** Stages 3–5 can disagree. Stage 3d (`ExploratoryAnalysisPlanner`) **never stops** the pipeline (“always allows execution”), but stage 5 **does** stop it when `investigation.executable() == false` or dimension is null in the SQL path.

---

## 2. Implicit assumptions

| Assumption | True? | Evidence |
|------------|-------|----------|
| Every question must have **exactly one metric** | **Mostly yes** for SQL | All templates take one `revenueMetric` / primary metric. Share-of-total is numerator+denominator but special-cased (`tipComposition`). |
| Every question must have **exactly one dimension** | **Yes for SQL** | `DeterministicAnalyticalQueryPlanner` skips steps with null dimension; legacy path requires `detectDimension() != null`. |
| Every question must become a **GROUP BY** query | **Almost always** | 6/6 SQL templates are grouped aggregations. Exceptions: tip/revenue composition (single-row ratio) and metric-pack queries. |
| Every question must fit the **same analytical shape** | **Yes** | Metric on Y, dimension on X, one aggregation function (SUM/AVG/COUNT) chosen by intent kind. |

**Additional hidden assumptions:**

- Registry bundle must expose metrics/dimensions (`SemanticCatalog.hasSchema()`); empty catalogue → schema resolver returns `UNRESOLVED`.
- Metric and dimension names must be **token-overlappable** with question phrases (CatalogQuestionMatcher threshold ≈ 0.35).
- Ranking questions must match **specific regexes** (`which X generates the highest Y`, `which X are most efficient`, etc.).
- Numeric dimensions get `_bucket` suffix unless temporal (`MetricResolutionEngine.bucketize`).
- Default table is `yellow_taxi_trips` when bundle has no entities (`AnalyticalQuestionResolver`, `DeterministicAnalyticalQueryPlanner`).
- Default metric is `total_amount` when resolution fails (`ExploratoryAnalysisPlanner` fallback at 0.3 confidence).

---

## 3. Stage-by-stage: inputs, outputs, failure conditions

### Stage 1 — Intent resolution

| | |
|---|---|
| **Input** | Raw question, tenant context |
| **Output** | `IntentResolution` (objective key, confidence), `Playbook` |
| **Failure** | Rarely blocks; misclassification is common |
| **Stops pipeline?** | No |

**Intent detectors (they conflict):**

- `AnalyticalIntentClassifier` — includes `CORRELATION`
- `QuestionSemanticExtractor.detectIntent()` — `"affect"` → `DISTRIBUTION`
- `SchemaDrivenQuestionResolver.detectIntent()` — default → `DISTRIBUTION`
- `AnalyticalSqlTemplateEngine.detectIntent()` — default → `CONTRIBUTION`

**Key files:** `planning/AnalyticalIntentClassifier.java`, `semantics/QuestionSemanticExtractor.java`, `semantics/catalog/SchemaDrivenQuestionResolver.java`, `execution/sqltemplates/AnalyticalSqlTemplateEngine.java`

---

### Stage 2 — Registry resolution

| | |
|---|---|
| **Input** | Intent |
| **Output** | `RegistryResolutionBundle` (entities, metrics, dimensions) |
| **Failure** | Empty metrics/dimensions → downstream catalog empty |
| **Stops pipeline?** | No directly; causes null resolution downstream |

**Key files:** `registry/` adapters, `SchemaDiscoveryAdapter`, `CatalogueMetricAdapter`

---

### Stage 3a — Investigation planner

| | |
|---|---|
| **Input** | Question, bundle |
| **Output** | `QuestionInvestigation` + `SemanticDiscoveryDebug` |
| **Failure** | `dimension.resolved() == false` OR `metricKey == null` |
| **Executable rule** | `shareAnalysis OR (dimension.resolved() AND metricKey != null)` |

```java
// QuestionInvestigationPlanner.java
boolean executable = extraction.isShareAnalysis()
        || (dimension.resolved() && extraction.metricKey() != null);
```

**Stops SQL?** **Yes** — if not executable, SQL planner returns `List.of()`.

**Key files:** `investigation/QuestionInvestigationPlanner.java`, `investigation/DimensionResolver.java`, `semantics/catalog/SemanticCatalogBuilder.java`, `semantics/catalog/SchemaDrivenQuestionResolver.java`, `semantics/catalog/CatalogQuestionMatcher.java`

---

### Stage 3b — Metric resolution

| | |
|---|---|
| **Input** | `QuestionSemantics`, bundle |
| **Output** | `MetricResolution` |
| **Failure** | `rejected=true`, null primaryMetric, confidence < 0.45 |
| **Usable** | `!rejected && primaryMetric != null && confidence >= 0.45` |

**Note:** `isUsable()` does **not** require dimension, but SQL planning effectively does.

**Key files:** `semantics/MetricResolutionEngine.java`, `semantics/MetricResolution.java`

---

### Stage 3c — Reasoning planner

| | |
|---|---|
| **Input** | Semantics + MetricResolution |
| **Output** | `QuestionDrivenReasoningPlan` with `QueryPlanStep[]` |
| **Failure** | Steps built even with weak resolution; steps may have null dimensions |
| **Stops pipeline?** | No |

Taxi-specific: trend default `pickup_hour`, tip-specific reasoning when `primaryMetric == "tip_amount"`.

**Key files:** `reasoning/AnalyticalReasoningPlanner.java`, `reasoning/QuestionDrivenReasoningPlan.java`

---

### Stage 3d — Exploratory planner

| | |
|---|---|
| **Input** | All of the above |
| **Output** | `ResolvedAnalyticalQuestion` — **always** |
| **Failure** | Never; falls back to `total_amount` at 0.3 confidence |

```java
// ExploratoryAnalysisPlanner.java
if (selected == null) {
    selected = new InterpretationCandidatePlan(
            "Unresolved analysis", question, "total_amount", "Total Revenue",
            null, "", ... GENERAL_ANALYSIS, 0.3, "fallback");
}
```

**Creates false confidence:** pipeline logs show a resolved metric even when investigation/SQL path is blocked.

**Key files:** `exploration/ExploratoryAnalysisPlanner.java`, `exploration/FallbackAnalyticalHeuristics.java`, `clarification/MetricFallbackHierarchy.java`

---

### Stage 4 — Investigation plan (meta)

| | |
|---|---|
| **Input** | Intent, resolved question, reasoning plan |
| **Output** | `InvestigationPlan` (intent type, depth, steps, dimensional focus) |
| **Failure** | Rarely blocks SQL directly |
| **Stops pipeline?** | No |

**Key files:** `planning/AnalyticalPlanningEngine.java`, `planning/QueryDecompositionEngine.java`

---

### Stage 5 — SQL planner (critical gate)

| | |
|---|---|
| **Input** | Intent, bundle, candidates, reasoningPlan, investigation |
| **Output** | `List<QuerySpec>` |
| **Hard stop #1** | `!investigation.executable()` → empty list |
| **Hard stop #2** | All reasoning steps skipped (null dimension) → empty list |
| **Hard stop #3** | Legacy path: `detectDimension() == null` → no primary spec |

```java
// DeterministicAnalyticalQueryPlanner.java
if (investigation != null && !investigation.executable()) {
    log.warn("[sql-planner] investigation blocked question={} reason={}",
            question, investigation.blockingReason());
    return List.of();
}
```

**Key files:** `execution/sqltemplates/DeterministicAnalyticalQueryPlanner.java`

---

### Stage 6 — SQL templates

| | |
|---|---|
| **Templates** | CONTRIBUTION, RANKING, TREND, COMPARISON, DISTRIBUTION, EFFICIENCY |
| **Missing** | CORRELATION, SHARE_OF_SEGMENT, FILTERED_AGGREGATE, MULTI_METRIC, LOOKUP |
| **Shape** | All grouped SELECT except tip composition |

**Key files:** `execution/sqltemplates/AnalyticalSqlTemplateEngine.java`, `*SqlTemplate.java`, `GroupedMetricSqlBuilder.java`, `IntentAggregationStrategy.java`, `DimensionBucketingSql.java`

---

### Stage 7 — Warehouse execution

| | |
|---|---|
| **Input** | `List<QuerySpec>` |
| **Output** | `List<QueryResult>` (rows) |
| **Failure** | BigQuery errors, zero rows after fallback chain |
| **Stops pipeline?** | No — continues with empty rows |

**Key files:** `execution/sqltemplates/AnalyticalSqlExecutionService.java`, `execution/sqltemplates/SqlFallbackExecutionChain.java`

---

### Stage 8 — Materialization (post-SQL)

| | |
|---|---|
| **Input** | Raw warehouse rows |
| **Output** | `MaterializedQueryResult` |
| **Failure** | No value column profiled, < 2 groups, re-grouping on wrong columns |
| **Recent patch** | Pre-aggregated passthrough for `{entity, metric}` rows — **post-SQL only**, does not fix planning |

**Key files:** `execution/materialization/AnalyticalQueryMaterializer.java`, `execution/materialization/GroupedWarehouseResultDetector.java`, `execution/framework/IntentDrivenComputationFramework.java`, `execution/framework/SchemaProfiler.java`

---

### Stage 9 — Validation / synthesis

| | |
|---|---|
| **Input** | Materialized results, investigation plan |
| **Output** | Findings, narrative, UI response |
| **Failure** | `rows_discarded_by_validation` when warehouse has rows but materialization/verification fails |
| **Stops pipeline?** | Skips synthesis on insufficient evidence |

**Key files:** `execution/repair/ResultQualityValidator.java`, `execution/repair/IntermediateResultInspector.java`, `runtime/DecisionRuntime.java`

---

### Pipeline checkpoint taxonomy

`DecisionRuntime.detectFirstEmptyStage()` order:

1. `question`
2. `resolved_metric`
3. `resolved_dimension`
4. `investigation_plan`
5. `generated_sql`
6. `materialized_query`

This is diagnostic only — **SQL can be empty while exploratory resolution still shows a metric**.

---

## 4. Hardcoded business concepts and dataset assumptions

### Phrase → column dictionaries (taxi)

| File | Concepts |
|------|----------|
| `semantic/SemanticDictionary.java` | airport, weekend, trip distance, pickup zone, tip, fare, vendor, payment type, revenue, zone |

### Metric/dimension defaults

| File | Defaults |
|------|----------|
| `execution/sqltemplates/HardMetricMappings.java` | `total_amount`, `fare_amount`, `tip_amount`, `trip_distance`, `PULocationID`, `weekend_flag`, `pickup_datetime` |

### Dataset profile

| File | Notes |
|------|-------|
| `execution/sqltemplates/DatasetProfileRegistry.java` | Only `nyc_taxi` profile; activated by taxi/fare/pickup keywords |

### SQL bucketing / derived dimensions

| File | Notes |
|------|-------|
| `execution/sqltemplates/DimensionBucketingSql.java` | Trip distance buckets, airport_fee, DAYOFWEEK weekend, hour extraction |
| `transforms/DerivedDimensionRegistry.java` | SemanticConcept → SQL derivation |
| `transforms/SemanticTransformationEngine.java` | Concept → derived dimension SQL |

### Fallback heuristics

| File | Notes |
|------|-------|
| `exploration/FallbackAnalyticalHeuristics.java` | Keyword → taxi columns |
| `exploration/SemanticFallbackDictionary.java` | Weekend, distance, zone plans |
| `candidate/CandidateAnalysisGenerator.java` | `inferDimension()` keyword → column |

### Special-case SQL

| File | Notes |
|------|-------|
| `DeterministicAnalyticalQueryPlanner.tipComposition()` | Tip + revenue questions only |

### Default table

| File | Fallback |
|------|----------|
| `AnalyticalQuestionResolver.java` | `"yellow_taxi_trips"` when bundle entities empty |
| `DeterministicAnalyticalQueryPlanner.resolveTable()` | Same |

### Schema-driven layer (partially generic, still pattern-bound)

| File | Notes |
|------|-------|
| `semantics/catalog/SchemaDrivenQuestionResolver.java` | Regex for ranking/efficiency only; no contribution %, correlation, trend, comparison templates |

### Other taxi-tied components

- `semantics/QuestionSemanticExtractor.resolveGrouping()` — bucket rules for `trip_distance`, `pickup_zone`, `fare_amount`, `tip_amount`
- `reasoning/AnalyticalReasoningPlanner` — `pickup_hour` trend default, tip-specific steps
- `grounding/PresentationLabelResolver` — taxi column labels
- `planning/DomainAnalyticalDefaults.java` — domain defaults

---

## 5. Question class analysis: succeed vs fail

### Ranking — *"Which oil field has the highest profit?"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Metric + dimension in catalogue; phrase overlap (`profit` → `profit_margin`, `oil field` → `oil_field`) |
| **Works when** | Regex matches (`has the highest`) + catalog match scores ≥ threshold + investigation executable |
| **Fails when** | Wording differs (`"top profit by oil field"`), catalogue empty, metric resolves but dimension doesn't (or reverse), synonym not in column name (`profitability` vs `profit_margin`) |
| **Why fixes feel brittle** | Success depends on regex + token overlap, not on question semantics |

---

### Contribution — *"What percentage of revenue comes from airport rides?"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Segment dimension (airport) + total metric + **share/composition** SQL path |
| **Works (taxi)** | `SemanticDictionary` maps "airport" → `airport_flag`; contribution regex fires; GROUP BY airport_flag |
| **Fails (oil/gas/other)** | "airport rides" not in catalogue; no generic "filter segment → compute share" planner |
| **Intent conflict** | May classify as CONTRIBUTION but still require a **grouping dimension** — percentage-of-segment is structurally a **filtered ratio**, not GROUP BY |
| **Supported shape?** | Partially — only via `SHARE_OF_TOTAL` special case (tip/revenue), not generic segment share |

---

### Correlation — *"How does downtime affect profitability?"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Two numeric series at **row level** (downtime_hours, profit_margin), not GROUP BY |
| **Planning** | `AnalyticalIntentClassifier` → `CORRELATION`; decomposition plans correlation steps |
| **SQL** | **No template.** `AnalyticalIntentKind` has no CORRELATION. Post-SQL `RelationshipAnalysisEngine` computes Pearson on returned rows |
| **Typical failure chain** | Extractor maps "affect" → DISTRIBUTION; schema resolver picks one metric + one dimension; investigation requires dimension → may pick wrong column; SQL emits GROUP BY or nothing; correlation never runs on correct raw data |
| **Supported?** | **No end-to-end.** Classification exists; SQL and investigation model do not support two-metric analysis |

---

### Trend — *"How has profit changed over time?"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Temporal dimension in catalogue (`date`, `month`, `period`) + trend intent + ORDER BY time |
| **Works when** | Temporal column exists and resolver maps "time"/"over time" to it |
| **Fails when** | No temporal column in bundle; schema resolver defaults intent to DISTRIBUTION; `TrendSqlTemplate` falls back to taxi `pickup_datetime` / hour extraction via transforms |
| **Shape mismatch** | Trend = time series; planner still produces single-dimension GROUP BY |

---

### Distribution — *"How is revenue distributed across product types?"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Dimension (`product_type`) + revenue metric + **SUM + share** (contribution shape) |
| **Intent conflict** | Word "distributed" → `DISTRIBUTION` intent → `COUNT(*)` template, **not SUM(revenue)** |
| **Works when** | Intent routed to CONTRIBUTION/RANKING despite wording; dimension resolves |
| **Fails when** | DISTRIBUTION template used → wrong aggregation; or dimension null |

---

### Comparison — *"Weekend vs weekday revenue"*

| Factor | Outcome |
|--------|---------|
| **Needs** | Binary segment dimension + comparison template |
| **Works (taxi)** | `weekend_flag` hardcoded in `ComparisonSqlTemplate`, `SemanticDictionary`, `DimensionBucketingSql` |
| **Fails (other datasets)** | No `weekend_flag` column; no generic "compare two values of same dimension" planner |
| **Supported?** | Only when dataset has taxi-equivalent columns or keyword inference hits |

---

## 6. Architectural bottlenecks

### Bottleneck 1: Single-shape SQL contract

All analytical questions are forced into **GROUP BY one dimension, aggregate one metric**. Correlation, segment share, and row-level comparison do not fit.

### Bottleneck 2: Multiple competing intent/resolution layers

At least **five intent detectors** and **three entity resolution paths** (dictionary, catalog regex, keyword heuristics). They are merged with ad hoc priority rules (`shouldPreferSchemaIntent` only for RANKING/EFFICIENCY). No single source of truth.

### Bottleneck 3: Investigation gate vs exploratory fallback

Exploratory layer says “resolved”; investigation gate blocks SQL. Operators see `resolved_metric` in logs but `generated_sql = NONE`.

### Bottleneck 4: Catalogue required but semantics still dictionary-backed

Registry is dataset-agnostic, but `QuestionSemanticExtractor` still falls back to `SemanticDictionary` and `matchFragment("revenue")`. Empty or partial catalogue → partial resolution.

### Bottleneck 5: Regex/template coverage gaps

Schema-driven resolver covers ~4 question shapes (which-highest, which-most, which-efficient, underperforming, by-X). Everything else falls through to dictionary or fails.

### Bottleneck 6: Post-SQL fixes don't fix planning

Materializer improvements accept pre-aggregated rows **after** SQL succeeds. Questions that never get SQL still fail at stage 5.

### Bottleneck 7: CORRELATION and advanced intents are planning-only

Rich `AnalyticalIntentType` enum (CORRELATION, ANOMALY, ROOT_CAUSE, …) with decomposition steps, but SQL layer only implements 6 grouped templates.

---

## 7. Question types: supported vs unsupported

| Question type | Planning support | SQL support | End-to-end |
|---------------|------------------|-------------|------------|
| Ranking (which X highest Y) | Partial (regex + catalog) | RANKING template | **Partial** — wording/catalog dependent |
| Contribution (X by Y) | Yes | CONTRIBUTION template | **Yes** — if dimension resolves |
| Share of total (tip/revenue) | Yes | Special composition SQL | **Yes** — taxi-specific |
| Segment share (X% from Y) | Weak | No dedicated template | **No** |
| Distribution (histogram/spread) | Yes | COUNT template | **Partial** — wrong agg for revenue questions |
| Trend over time | Partial | TREND template | **Partial** — needs temporal column |
| Comparison (A vs B) | Partial | COMPARISON template | **Partial** — taxi columns |
| Efficiency | Partial | AVG template | **Partial** |
| Correlation (X affects Y) | Classified | **None** | **No** |
| Anomaly / root cause | Classified | **None** | **No** |
| Lookup / exact | Minimal | **None** | **No** |
| Multi-metric | No | No | **No** |
| Filtered aggregate (WHERE segment) | No | No | **No** |

---

## 8. Why per-question fixes keep failing

Each fix targets **one layer's symptom**:

| Fix type | Layer patched | Next question fails because |
|----------|---------------|----------------------------|
| Add regex for "which X highest Y" | Schema resolver | Contribution/correlation uses different grammar |
| Add column token for `profit_margin` | Catalog matcher | Trend needs temporal column, not metric match |
| Pre-aggregated materializer passthrough | Post-SQL | SQL never generated for blocked investigation |
| Metric alias instead of `revenue` | SQL template | Dimension still null for new phrasing |
| Airport/weekend bindings | Dictionary | Irrelevant on non-taxi datasets |

**Root cause:** There is no **question-type → analytical plan → SQL shape** registry. Instead, every question is squeezed through one GROUP BY template after overlapping heuristics fight over metric/dimension.

---

## 9. Recommended redesign (dataset-agnostic analytical engine)

Design direction only — not implementation.

### 9.1 Separate concerns into three layers

```
Layer A: Question understanding
  Input:  question + schema catalog
  Output: AnalyticalPlan (typed, not columns yet)
          e.g. Rank(metric, by dimension)
               Share(metric, where segment)
               Correlate(metricA, metricB)
               Trend(metric, over timeDimension)
               Compare(metric, dimension, values [A,B])

Layer B: Plan binding
  Input:  AnalyticalPlan + RegistryResolutionBundle
  Output: BoundPlan (concrete columns, aggregation, filters)
          Validates all references exist in schema — no dictionary

Layer C: SQL emission
  Input:  BoundPlan
  Output: QuerySpec(s)
          One renderer per plan type — not one template per keyword
```

### 9.2 Replace investigation executable gate

Instead of `dimension.resolved() AND metricKey != null` for all questions:

- Each **plan type** declares required bindings (rank needs 2, correlate needs 2 metrics, share needs 1 metric + 1 filter).
- SQL blocked only when **BoundPlan** is incomplete for its type — not when a generic dimension is missing.

### 9.3 Single intent resolution

One classifier produces `AnalyticalPlan` type. Remove parallel detectors or make them features feeding one model, not competing overrides.

### 9.4 Schema-first, dictionary-never for core path

`SemanticDictionary` and `HardMetricMappings` become optional **display enrichments**, not resolution inputs. All binding from catalogue columns + question token match against catalog only.

### 9.5 SQL shape library matched to plan types

| Plan type | SQL shape |
|-----------|-----------|
| Rank | `GROUP BY dim ORDER BY agg DESC` |
| Breakdown / distribution of metric | `GROUP BY dim, SUM(metric), share` |
| Segment share | `SUM(CASE WHEN … THEN metric END) / SUM(metric)` |
| Trend | `GROUP BY time_trunc ORDER BY time` |
| Compare | `GROUP BY dim WHERE dim IN (A,B)` or pivot |
| Correlate | `SELECT colA, colB FROM table` (row-level, no GROUP BY) |
| Efficiency | `GROUP BY dim, AVG(ratio)` or derived |

### 9.6 Post-SQL follows plan type

Materializer consumes **BoundPlan** metadata (which column is dimension, which is metric, whether rows are pre-aggregated) instead of re-inferring from row shape.

### 9.7 Explicit failure reporting

When binding fails, report **which plan type was inferred** and **which binding slot is missing** (e.g. `"CorrelatePlan: missing second metric 'profitability'"`), not generic `resolved_dimension = null`.

---

## 10. Key file index

| Area | Files |
|------|-------|
| Entry | `runtime/DecisionRuntime.java`, `api/DecisionController.java` |
| Semantic resolution | `clarification/AnalyticalQuestionResolver.java`, `clarification/SemanticResolution.java` |
| Investigation | `investigation/QuestionInvestigationPlanner.java`, `investigation/DimensionResolver.java` |
| Semantics | `semantics/QuestionSemanticExtractor.java`, `semantics/MetricResolutionEngine.java` |
| Schema catalog | `semantics/catalog/SchemaDrivenQuestionResolver.java`, `semantics/catalog/CatalogQuestionMatcher.java`, `semantics/catalog/SemanticCatalogBuilder.java` |
| Reasoning / query plan | `reasoning/AnalyticalReasoningPlanner.java`, `reasoning/QuestionDrivenReasoningPlan.java` |
| SQL planning | `execution/sqltemplates/DeterministicAnalyticalQueryPlanner.java` |
| SQL templates | `execution/sqltemplates/AnalyticalSqlTemplateEngine.java`, `*SqlTemplate.java`, `GroupedMetricSqlBuilder.java` |
| Transforms | `transforms/SemanticTransformationEngine.java`, `transforms/SemanticQueryRewriter.java` |
| Hardcoding | `semantic/SemanticDictionary.java`, `execution/sqltemplates/HardMetricMappings.java`, `execution/sqltemplates/DatasetProfileRegistry.java`, `exploration/FallbackAnalyticalHeuristics.java` |
| Execution | `execution/sqltemplates/AnalyticalSqlExecutionService.java` |
| Materialization | `execution/materialization/AnalyticalQueryMaterializer.java`, `execution/framework/IntentDrivenComputationFramework.java` |
| Intent routing | `planning/AnalyticalIntentClassifier.java`, `planning/AnalyticalIntentType.java`, `execution/sqltemplates/AnalyticalIntentKind.java` |
| Correlation (post-SQL only) | `analytics/RelationshipAnalysisEngine.java`, `analytics/AnalyticalDepthEngine.java` |

---

## 11. Conclusion

The recurring break-fix-break cycle is expected given the current architecture:

1. **Planning assumes one metric, one dimension, one GROUP BY** for almost all questions.
2. **Six layers** resolve intent and entities independently, then **SQL is hard-gated** on investigation executable + dimension presence.
3. **Taxi concepts** remain embedded in fallbacks even as catalogue-driven paths are added alongside them.
4. **Advanced question classes** (correlation, segment share, comparison without binary flag) are **classified in planning but have no SQL plan type**.

Until the engine has **typed analytical plans** bound to schema — rather than keyword → column → single template — each new question class will require another patch in a different layer.

---

## Next steps (when ready to implement)

1. Design `AnalyticalPlan` / `BoundPlan` model with plan-type-specific binding requirements.
2. Replace investigation `executable` gate with plan-type binding validation.
3. Consolidate intent detection into a single classifier feeding plan types.
4. Add SQL renderers for missing plan types (Correlate, SegmentShare, Compare).
5. Demote `SemanticDictionary` and `HardMetricMappings` to display-only enrichments.
6. Improve failure messages to report plan type + missing binding slots.
