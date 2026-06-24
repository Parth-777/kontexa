# Analytical Model Architecture Plan

Based on source-code inspection of the decision pipeline (investigation, reasoning, SQL planner, and runtime gates). No code changes — architectural analysis only.

---

## Executive Summary

The pipeline has **three parallel intent enums** and **one SQL shape family**. Planning and narrative layers recognize ~15 intent types; investigation recognizes 8; SQL generation recognizes **6**, all variants of `SELECT … GROUP BY dimension, AGG(metric)`.

**Core limitation:** The investigation executable gate and SQL template library are both parameterized as `f(metric, dimension)`, but a significant class of business questions is `f(metric_a, metric_b)` or `rank(metric) by dimension` where the blocker for the first class is the gate itself, not missing SQL capability.

Correlation is partially built (classifier, metric requirements, post-warehouse `RelationshipAnalysisEngine`) but never connected to question-bound metric pairs at investigation or SQL compilation time.

---

## 1. What Question Types Are Supported Today?

### Layer A — Investigation + SQL (what actually runs)

Only patterns that pass this gate reach `DeterministicAnalyticalQueryPlanner`:

```java
// QuestionInvestigationPlanner.java:66-67
boolean executable = extraction.isShareAnalysis()
        || (dimension.resolved() && extraction.metricKey() != null);
```

| Pattern | Investigation intent | Required inputs | Executable gate | SQL template | SQL shape |
|---------|------------------------|-----------------|-----------------|--------------|-----------|
| **Contribution / breakdown** | `CONTRIBUTION` | `metricKey` + resolved dimension | `dimension.resolved && metricKey != null` | `ContributionSqlTemplate` | `SUM(metric) GROUP BY dim [, share_pct]` |
| **Ranking** | `RANKING` | `metricKey` + resolved dimension | same | `RankingSqlTemplate` | same + `ORDER BY … DESC LIMIT 10` |
| **Comparison** | `COMPARISON` | `metricKey` + resolved dimension | same | `ComparisonSqlTemplate` | `SUM(metric) GROUP BY derived segment` |
| **Distribution** | `DISTRIBUTION` | `metricKey` + resolved dimension | same | `DistributionSqlTemplate` | `COUNT(*) GROUP BY dim [, share_pct]` |
| **Trend** | `TREND` | `metricKey` + temporal dimension | same | `TrendSqlTemplate` | `SUM(metric) GROUP BY time bucket` |
| **Efficiency** | `EFFICIENCY` | `metricKey` + resolved dimension | same | `EfficiencySqlTemplate` | `AVG(metric) GROUP BY dim` |
| **Share-of-total (special)** | `SHARE_OF_TOTAL` | `metricKey` + `targetMetricKey` | **`isShareAnalysis()` only** — no dimension | hardcoded `tipComposition()` in planner | scalar ratio query, no GROUP BY |

All six templates delegate to `GroupedMetricSqlBuilder` — every SQL path is **one metric, one GROUP BY column, one aggregation**:

```java
// GroupedMetricSqlBuilder.java:17-20
public String renderGroupedQuery(TemplateContext ctx) {
    AggregationSpec spec = strategy.forSqlIntent(ctx.intent(), ctx.revenueMetric());
    return renderGroupedQuery(ctx, spec);
}
```

`AnalyticalIntentKind` is the complete SQL routing enum — **no CORRELATION, no METRIC_PAIR, no scalar lookup**:

```java
// AnalyticalIntentKind.java
public enum AnalyticalIntentKind {
    CONTRIBUTION,
    TREND,
    RANKING,
    COMPARISON,
    DISTRIBUTION,
    EFFICIENCY
}
```

### Layer B — Classifier / reasoning (declared but not fully wired)

`AnalyticalIntentType` declares CORRELATION, RETENTION, ANOMALY_DETECTION, FORECASTING, ROOT_CAUSE_INVESTIGATION, etc. These drive **planning, metric requirements, and post-warehouse analytics** — not SQL templates.

`AnalyticalRelationship` (what `AnalyticalReasoningPlanner.buildQueryPlan` switches on) has **no CORRELATION or METRIC_PAIR**:

```java
// AnalyticalRelationship.java
public enum AnalyticalRelationship {
    SHARE_OF_TOTAL,
    DIMENSION_BREAKDOWN,
    TREND_OVER_TIME,
    RANKING,
    COMPARISON,
    DISTRIBUTION,
    EFFICIENCY,
    EXACT_LOOKUP
}
```

`buildQueryPlan` only emits steps for those relationships. Anything else falls through to a generic `CONTRIBUTION` step — still requiring a dimension at SQL time:

```java
// AnalyticalReasoningPlanner.java:138-170
switch (s.relationship()) {
    case SHARE_OF_TOTAL -> { ... }
    case TREND_OVER_TIME -> ...
    case RANKING -> ...
    // no CORRELATION case
}
if (plans.isEmpty()) {
    plans.add(query("primary", metric, dim, grouping,
            mapSqlIntent(s.intent()), "Primary analytical query"));
}
```

### Layer C — Post-warehouse correlation (not question-driven)

`RelationshipAnalysisEngine` computes Pearson *r* on **already-returned row sets** — it probes dimension×value and volume×value pairs from result columns. It does not compile question-specified metric pairs into SQL:

```java
// RelationshipAnalysisEngine.java:38-44
// dimension × value relationships
for (String dim : dimCols) {
    for (String val : valueCols) {
        RelationshipSignal s = compute(rows, dim, val);
```

So CORRELATION is a **downstream interpretation** of grouped-aggregate output, not a first-class query pattern.

---

## 2. What Question Types Are Impossible to Represent Today?

| Question type | Example | Why impossible |
|---------------|---------|----------------|
| **Metric vs metric (correlation)** | Does carbon emission correlate with profit margin? | Classifier → `CORRELATION`; no `AnalyticalInvestigationIntent`, no `AnalyticalRelationship`, no SQL template. Investigation still requires a dimension. |
| **Metric vs metric (impact/affect)** | How does downtime affect profitability? | AFFECT regex extracts `"downtime"` as business entity; `DimensionResolver` expects a **dimension column** — `downtime_hours` is a metric → `executable=false`. |
| **Relationship / driver** | How does maintenance cost affect revenue? | Same: driver is a metric, outcome is a metric; no slot for `metric_x → metric_y`. |
| **Ranking without dimension** | Top N by metric alone | Gate requires `dimension.resolved`. |
| **Ranking with unresolved catalog** | Which oil field generates highest profit? (sparse registry) | `SchemaDrivenQuestionResolver` needs `WHICH_HIGHEST` regex + catalog match; empty/sparse `RegistryResolutionBundle` → dimension or metric UNRESOLVED → gate fails. Works in tests with populated bundle; fails on unprofiled datasets. |
| **New dataset questions generally** | Any question on unregistered schema | `SemanticCatalogBuilder.build()` returns empty catalog when bundle has no metrics/dimensions → matcher scores 0 → dimension null → gate fails. |
| **RETENTION / ANOMALY / FORECAST / ROOT_CAUSE** | — | Planning + `MetricRequirementResolver` only; no SQL path. |
| **EXACT_LOOKUP** | What is total revenue? | Investigation intent exists; no scalar SQL template; gate requires dimension unless share. |

### Specific misclassification that causes failures

For affect/impact questions, three components **agree on the wrong model**:

1. `QuestionSemanticExtractor.detectIntent` — `"affect"` → `DISTRIBUTION` (line 139)
2. `SchemaDrivenQuestionResolver.detectIntent` — same (line 163–165)
3. `QuestionInvestigationPlanner.subjectPhrase` — AFFECT regex sets entity phrase to the **driver** (`"downtime"`), then `DimensionResolver` tries to bind it as a GROUP BY column

The question is semantically **metric Y ~ f(metric X)**, but the pipeline treats it as **metric X GROUP BY dimension**.

---

## 3. Exact Code Path for the Executable Gate

```
AnalyticalQuestionResolver.resolveFull()
  └─ investigationPlanner.plan(question, bundle)          // QuestionInvestigationPlanner.java:58
       ├─ schemaResolver.resolve()                          // metric/dimension hints from catalog
       ├─ semanticExtractor.extract() + overlaySchema()
       ├─ extractEntities()                                 // AFFECT/CONTRIBUTE regex → businessEntityPhrase
       ├─ dimensionResolver.resolve(extraction, ...)        // DimensionResolver.java:32–78
       │    └─ if !shareAnalysis && entityKey null → unresolved
       ├─ executable = shareAnalysis OR (dimension.resolved && metricKey != null)  // line 66–67
       └─ return QuestionInvestigation(..., executable, ...)

DecisionRuntime (line 274)
  └─ deterministicPlanner.plan(..., investigation)
       └─ if (!investigation.executable()) return List.of()  // DeterministicAnalyticalQueryPlanner:78–81
```

### Why this blocks valid questions

The gate encodes a single hypothesis — *every analytical question is a segmented aggregation*. Metric-pair questions never set `dimension.resolved=true` because the "entity" in the question is a **metric column**, and `DimensionResolver` only maps to dimension registry entries:

```java
// DimensionResolver.java:48-61
if (catalog != null && catalog.hasSchema()) {
    CatalogQuestionMatcher.MatchResult match = entityKey != null && !entityKey.isBlank()
            ? catalogMatcher.bestDimension(question, entityKey, catalog)
            : catalogMatcher.bestDimension(question, phrase, catalog);
    ...
}
if (entityKey == null || entityKey.isBlank()) {
    return ResolvedDimension.unresolved(
            phrase,
            "Could not resolve business entity to a warehouse dimension.");
}
```

Even when `MetricResolutionEngine` succeeds (`isUsable=true`, `grouping=composition`), the SQL planner **never runs** because investigation blocks first. `MetricResolution.isUsable()` only checks metric + confidence — it does **not** require a dimension:

```java
// MetricResolution.java:22-24
public boolean isUsable() {
    return !rejected && primaryMetric != null && !primaryMetric.isBlank() && confidence >= 0.45;
}
```

So reasoning can be fully planned while SQL is hard-blocked — the architectural mismatch is between **metric resolution** (permissive) and **investigation executable** (requires dimension).

---

## 4. Proposed Generalized Analytical Model (Schema-Agnostic)

Replace the implicit "metric + dimension → GROUP BY" assumption with an explicit **analysis pattern** and **binding slots** resolved from `SemanticCatalog` (registry columns + types, no domain hardcoding).

### Core types

```
AnalyticalPattern          // what computation shape
  GROUPED_AGGREGATION      // A, B, C — metric + dimension
  METRIC_RELATIONSHIP      // D, E — metric + metric
  SHARE_COMPOSITION        // contribution without segment dim
  SCALAR                   // single aggregate

BindingSlots               // resolved from catalog, not regex alone
  primaryMetric            // required for most patterns
  secondaryMetric          // outcome metric (relationship) or denominator (share)
  groupByColumn            // dimension OR bucketed numeric column
  groupByKind              // CATEGORICAL | NUMERIC_BUCKET | TEMPORAL | NONE
  orderBy                  // for ranking
  limit                    // for ranking

ResolvedAnalysis           // output of investigation
  pattern: AnalyticalPattern
  slots: BindingSlots
  executable: pattern-specific predicate
  confidence: double
```

### Pattern-specific executable gates (replace current single gate)

| Pattern | Required slots | Executable when |
|---------|----------------|-----------------|
| **GROUPED_AGGREGATION** (Contribution, Comparison, Distribution) | `primaryMetric`, `groupByColumn` | both resolved |
| **RANKING** | `primaryMetric`, `groupByColumn`, `orderBy` | both resolved |
| **TREND** | `primaryMetric`, temporal `groupByColumn` | both resolved |
| **EFFICIENCY** | `primaryMetric`, `groupByColumn` (or derived ratio cols) | both resolved |
| **METRIC_RELATIONSHIP** (Correlation, Impact) | `primaryMetric` (driver), `secondaryMetric` (outcome) | both resolved; **no dimension required** |
| **SHARE_COMPOSITION** | `primaryMetric`, `secondaryMetric` (total) | both resolved |
| **SCALAR** | `primaryMetric` | metric resolved |

### Pattern-specific SQL templates (new)

| Pattern | SQL shape (schema-driven column names) |
|---------|----------------------------------------|
| GROUPED_AGGREGATION | `SELECT dim, AGG(m) … GROUP BY dim` (existing templates) |
| RANKING | same + `ORDER BY AGG(m) DESC LIMIT n` |
| METRIC_RELATIONSHIP — correlation | `SELECT CORR(driver, outcome) AS r FROM table` or bucket driver → `SELECT bucket, AVG(outcome) … GROUP BY bucket` |
| METRIC_RELATIONSHIP — impact | `SELECT CORR / COVAR / bucketed AVG` depending on cardinality of driver |
| SHARE_COMPOSITION | `SELECT SUM(numerator), SUM(denominator), ratio` (generalize current `tipComposition`) |

### Resolution flow (schema-driven, no taxi/oil special cases)

1. **Classify pattern** from question structure (regex/grammar): `which X highest Y` → RANKING; `how does X affect Y` → METRIC_RELATIONSHIP; `X by Y` → GROUPED_AGGREGATION; `correlate` → METRIC_RELATIONSHIP.
2. **Bind slots** via `CatalogQuestionMatcher` against `SemanticCatalog` built from registry — match phrases to **metrics OR dimensions** based on column role in catalog, not assumed role from question position.
3. **Derive grouping** only when pattern requires it: NUMERIC metrics used as drivers get bucketization; CATEGORICAL dimensions group as-is.
4. **Gate on pattern**, not on dimension alone.
5. **Route to template** by `AnalyticalPattern`, not by collapsing everything into `AnalyticalIntentKind.CONTRIBUTION`.

---

## 5. Current Model vs Proposed Model

### Current model

```
Question
  → classify intent (15 types, inconsistent across layers)
  → resolve ONE metric + ONE dimension (dimension mandatory for executable)
  → investigation.executable = shareAnalysis OR (dimension OK AND metric OK)
  → SQL = one of 6 GROUP BY templates
  → correlation/impact = post-hoc on grouped rows (if any)
```

**Implicit assumption:** All analytics are segmented aggregations.

### Proposed model

```
Question
  → classify AnalyticalPattern (5 patterns)
  → bind slots from SemanticCatalog (metrics, dimensions, roles)
  → investigation.executable = pattern-specific slot check
  → SQL = pattern-matched template (GROUP BY family OR metric-pair family OR scalar)
  → interpretation layer consumes pattern-appropriate result shape
```

**Explicit assumption:** Questions declare a *computation shape* first; grouping dimension is optional depending on shape.

### Which failing questions would work under the new model

| Question | Current failure | Proposed pattern | Slots bound | Works because |
|----------|-----------------|------------------|-------------|---------------|
| How does downtime affect profitability? | `downtime` treated as dimension → unresolved → `executable=false` | **METRIC_RELATIONSHIP** | driver=`downtime_hours`, outcome=`profit_margin` | No dimension required; CORR or bucketed AVG SQL |
| Does carbon emission correlate with profit margin? | CORRELATION intent with no SQL path; dimension required | **METRIC_RELATIONSHIP** | driver=`carbon_emission`, outcome=`profit_margin` | `CORR(col_a, col_b)` template |
| How does maintenance cost affect revenue? | Same as downtime | **METRIC_RELATIONSHIP** | driver=`maintenance_cost`, outcome=`total_revenue` | Same |
| Which oil field generates the highest profit? | Works with full catalog; **fails on sparse/new registry** | **RANKING** (GROUPED_AGGREGATION) | metric=`profit_margin`, groupBy=`oil_field` | Same shape as today, but gate checks ranking slots not generic dimension; catalog binding improved with synonym scoring independent of regex |
| Many questions on new datasets | Empty catalog → all slots null → gate fails | All patterns | Slots from any registered columns | Gate fails gracefully per-pattern with "missing slot X"; profiling populates catalog once, all patterns share same binder |

### Which working questions stay working

| Question | Current pattern | Proposed pattern | Change |
|----------|-----------------|------------------|--------|
| Revenue contribution by airport rides | CONTRIBUTION + dim | GROUPED_AGGREGATION | None — same SQL |
| Revenue contribution by trip distance | CONTRIBUTION + bucketed numeric dim | GROUPED_AGGREGATION | None — bucket derivation stays |
| Weekend vs weekday revenue | COMPARISON + dim | GROUPED_AGGREGATION | None |

---

## Key Source Files

| Area | Path |
|------|------|
| Executable gate | `investigation/QuestionInvestigationPlanner.java` |
| SQL hard block | `execution/sqltemplates/DeterministicAnalyticalQueryPlanner.java` |
| Dimension binding | `investigation/DimensionResolver.java` |
| Metric binding | `semantics/MetricResolutionEngine.java` |
| SQL templates (6 types) | `execution/sqltemplates/*SqlTemplate.java` |
| SQL routing enum | `execution/sqltemplates/AnalyticalIntentKind.java` |
| Investigation intents | `investigation/AnalyticalInvestigationIntent.java` |
| Classifier intents | `planning/AnalyticalIntentType.java` |
| Reasoning relationships | `semantics/AnalyticalRelationship.java` |
| Post-warehouse correlation | `analytics/RelationshipAnalysisEngine.java` |
| Schema catalog | `semantics/catalog/SemanticCatalogBuilder.java` |
| Runtime pipeline | `runtime/DecisionRuntime.java` |
| Diagnostic test harness | `src/test/java/.../diagnostics/PipelineFailurePathDiagnosticTest.java` |

---

## Related Documents

- `docs/planner-architecture-diagnostic-report.md` — pipeline termination and planner overlap analysis
- Run concrete traces: `mvn test -Dtest=PipelineFailurePathDiagnosticTest`
