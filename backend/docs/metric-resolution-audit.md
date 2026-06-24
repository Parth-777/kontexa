# Metric Resolution Layer Audit

Audit of metric resolution for five oil-domain questions against a populated `oil_operations` registry bundle. Scores reproduced by `MetricResolutionAuditTest` (`mvn test -Dtest=MetricResolutionAuditTest`).

**Registry metrics:** `profit_margin`, `total_revenue`, `downtime_hours`, `maintenance_cost`, `carbon_emission`  
**Registry dimensions:** `oil_field`, `region`, `facility_type`, `product_type`

---

## Resolution Pipeline (actual code)

```
Question
  ├─ SemanticDictionary.matchAll()          → NYC-taxi phrases only (QueryEntityResolver)
  ├─ CatalogQuestionMatcher.bestMetric()    → token/substring vs SemanticCatalog labels
  ├─ SchemaDrivenQuestionResolver           → regex hints + bestMetric(question, hint)
  ├─ QuestionSemanticExtractor              → dictionary + catalog enrich + pickPrimaryMetric()
  └─ MetricResolutionEngine.resolve()       → registry validation + fuzzy column match + confidence boost
```

**Scoring formula** (`CatalogQuestionMatcher.scoreEntry`):
- Substring hit: `0.85 + (phraseLen / textLen) * 0.1`
- Token overlap (≥50% of phrase tokens): `0.45 + (overlap / phraseTokenCount) * 0.45`
- Final score × `entry.rankScore()` (catalog list order penalty)
- Winner must score **≥ 0.35**

**Humanized labels** come from `SemanticCatalogBuilder.humanize()` — e.g. `profit_margin` → `"profit margin"`. No synonym table, no stemming beyond naive singularization (`profits` → `profit`).

---

## Question 1 — Which oil field generates the highest profit?

### 1. Raw extracted terms

| Source | Output |
|--------|--------|
| `WHICH_HIGHEST` regex | dimHint=`"oil field"`, metricHint=`"profit"` |
| `SemanticDictionary` | *(none — taxi-only)* |
| `matchFragment("profit")` | `null` |
| `matchFragment("oil field")` | `null` |
| `SchemaDrivenQuestionResolver` | metric=`profit_margin`, intent=`RANKING` |

### 2–4. Candidate metrics

**Full question text** (normalized: `which oil field generates the highest profit`):

| Column | Label | Score | Passes 0.35 |
|--------|-------|-------|-------------|
| **profit_margin** | profit margin | **0.6750** | yes |
| total_revenue | total revenue | 0.0000 | no |
| downtime_hours | downtime hours | 0.0000 | no |
| maintenance_cost | maintenance cost | 0.0000 | no |
| carbon_emission | carbon emission | 0.0000 | no |

**With metric hint `"profit"`** (schema path: `hint + question`):

| Column | Score |
|--------|-------|
| **profit_margin** | **0.6750** |

**Winner:** `profit_margin` @ 0.675 — `bestMetric(question, "profit")` via token overlap: question token `profit` matches 1/2 tokens of phrase `profit margin` → `0.45 + 0.5*0.45 = 0.675`.

### 5. Final resolution

| Stage | primaryMetric | targetMetric | isUsable |
|-------|---------------|--------------|----------|
| Extractor | profit_margin | null | confidence 0.85 |
| Engine | profit_margin | null | **yes** (0.98) |

### Why profit → profit_margin (works here)

- Token `"profit"` overlaps `"profit margin"` (1/2 → score 0.675).
- Substring `"profit"` is **not** contained in normalized text as full phrase `"profit margin"`, so substring rule (0.85+) does not fire — win is **token-only**.

### Failure mode on sparse registry

If `profit_margin` is absent from the bundle, all scores stay 0 → `pickCatalogMetric()` falls back to `matchFragment("revenue")` → `total_amount` (taxi column not in oil registry) → `isUsable` may fail or wrong metric.

---

## Question 2 — How does downtime affect profitability?

### 1. Raw extracted terms

| Source | Output |
|--------|--------|
| `AFFECT` regex | driver=`"downtime"`, outcome=`"profitability"` |
| `SemanticDictionary` | *(none)* |
| `matchFragment("downtime")` | `null` |
| `matchFragment("profitability")` | `null` |
| `SchemaDrivenQuestionResolver` | metric=`downtime_hours`, intent=`DISTRIBUTION` |

### 2–4. Candidate metrics

**Full question text:**

| Column | Score | Passes 0.35 |
|--------|-------|-------------|
| **downtime_hours** | **0.6615** | yes |
| profit_margin | 0.0000 | no |
| *(others)* | 0.0000 | no |

**With hint `"profitability"`** (AFFECT outcome — **not used by SchemaDrivenQuestionResolver today**):

| Column | Score |
|--------|-------|
| **downtime_hours** | **0.6615** |
| profit_margin | 0.0000 |

Hint prepends `"profitability "` to question; `"downtime"` tokens still match `downtime hours` (2/2 → 0.6615). **`profitability` never matches `profit margin`** (0/2 tokens).

**Winner:** `downtime_hours` — full-question token match on `"downtime"`, not the outcome phrase.

### 5. Final resolution

| Stage | primaryMetric | targetMetric | Notes |
|-------|---------------|--------------|-------|
| Extractor | downtime_hours | **null** | Outcome `"profitability"` ignored |
| Engine | downtime_hours | null | isUsable=true |

### Why profitability fails to map to profit_margin

| Term | Token overlap with `profit margin` | Score with hint |
|------|--------------------------------------|-----------------|
| `profit` | 1/2 | 0.6750 |
| `profitability` | **0/2** | **0.0000** |
| `profitable` | **0/2** | **0.0000** |

**Root causes:**
1. No morphological link (`profitability` ≠ token `profit` — tokenizer keeps full word).
2. No business synonym map (`profitability` → `profit_margin`).
3. `QuestionSemanticExtractor.pickPrimaryMetric()` has no AFFECT branch — driver/outcome not split; `pickTargetMetric()` only handles `CONTRIBUTE_TO`.
4. Schema resolver binds metric from **full question**, not AFFECT outcome group.

---

## Question 3 — Does carbon emission correlate with profit margin?

### 1. Raw extracted terms

| Source | Output |
|--------|--------|
| `CORRELATE` regex *(audit only — not in production resolver)* | metricA=`"carbon emission"`, metricB=`"profit margin"` |
| `SemanticDictionary` | *(none)* |
| `matchFragment("carbon emission")` | `null` |
| `matchFragment("profit margin")` | `null` |
| `SchemaDrivenQuestionResolver` | metric=`profit_margin`, intent=`DISTRIBUTION` |

### 2–4. Candidate metrics

**Full question text:**

| Column | Score | Passes 0.35 |
|--------|-------|-------------|
| **profit_margin** | **0.9000** | yes |
| carbon_emission | 0.8640 | yes |
| *(others)* | 0.0000 | no |

**Winner:** `profit_margin` @ 0.90 — substring `"profit margin"` contained in normalized question (0.85+ rule).

Both metrics pass threshold, but **only one primary** is kept; `targetMetric` stays null (no correlation slot).

### 5. Final resolution

| Stage | primaryMetric | targetMetric |
|-------|---------------|--------------|
| Extractor | profit_margin | null |
| Engine | profit_margin | null |

### Why profit/profitability mapping

- `"profit margin"` works via **exact substring** in question text.
- `"profitability"` would still fail (see Q2).
- **Gap:** second metric `carbon_emission` is scored (0.864) but discarded — no dual-metric binding.

---

## Question 4 — What drives profitability?

### 1. Raw extracted terms

| Source | Output |
|--------|--------|
| Keyword | `"drive"` → extractor intent `DISTRIBUTION` |
| No AFFECT regex | pattern requires `how does X drive Y` |
| `SemanticDictionary` | *(none)* |
| `SchemaDrivenQuestionResolver` | metric **UNRESOLVED**, intent=`DISTRIBUTION` |

### 2–4. Candidate metrics

**Full question and hint `"profitability"`:**

| Column | Score | Passes 0.35 |
|--------|-------|-------------|
| *(all)* | **0.0000** | **no** |

**Winner:** UNRESOLVED — `"profitability"` does not token-match any catalog phrase; question has no `"downtime"`, `"carbon"`, etc.

### 5. Final resolution

| Stage | primaryMetric | isUsable |
|-------|---------------|----------|
| Extractor | **total_amount** | confidence 0.25 |
| Engine | total_amount | **false** (0.30 < 0.45) |

**Fallback path:** `pickCatalogMetric()` → catalog unresolved → `matchFragment("revenue")` → taxi `total_amount` (not in oil registry). Hardcoded NYC taxi default.

### Why profitability fails

Same token gap as Q2. No regex extracts outcome in isolation without `how does … drive` structure. Open-ended driver questions have **no metric hint path**.

---

## Question 5 — Which facility type is most profitable?

### 1. Raw extracted terms

| Source | Output |
|--------|--------|
| `WHICH_IS_MOST` *(audit regex — **not in SchemaDrivenQuestionResolver**)* | dimHint=`"facility type"`, adjHint=`"profitable"` |
| `SchemaDrivenQuestionResolver` | metric **UNRESOLVED** (no matching regex) |
| `matchFragment("profitable")` | `null` |

Production resolver tries `WHICH_ARE` (`which X are most Y`) — **does not match** `which X is most Y`.

### 2–4. Candidate metrics

**Full question and hint `"profitable"`:**

| Column | Score | Passes 0.35 |
|--------|-------|-------------|
| *(all)* | **0.0000** | **no** |

**Winner:** UNRESOLVED

### 5. Final resolution

| Stage | primaryMetric | dimension | isUsable |
|-------|---------------|-----------|----------|
| Extractor | **total_amount** | facility_type | **yes** (0.90) |
| Engine | total_amount | facility_type | yes |

**Failure:** Dimension resolves (`facility_type` token match) but metric falls back to taxi `total_amount` instead of `profit_margin`. Ranking question gets wrong metric.

### Why profitable fails

- `"profitable"` → 0/2 token overlap with `profit margin`.
- No adjective→metric normalization (`profitable` → `profit_margin`).
- Missing regex: `which (.+) is most (.+)` in `SchemaDrivenQuestionResolver`.

---

## Cross-Cutting Findings

### Three resolution sources conflict

| Source | Scope | Profit handling |
|--------|-------|-----------------|
| `SemanticDictionary` | Hardcoded NYC taxi | No oil/profit entries |
| `CatalogQuestionMatcher` | Registry catalog | Token/substring only |
| `MetricSemanticRegistry` | Taxi defaults + fuzzy | `profit_margin` not registered; generic fallback |

### MetricResolutionEngine does not re-score

Engine **validates** extractor output against registry keys (`resolveColumn`, `fuzzyMatch`). It does **not** re-run catalog matching or fix wrong primaries. Pass-through preserves extractor mistakes (Q5 `total_amount`).

### Outcome / second metric never bound

| Pattern | Extractor support | targetMetric |
|---------|-------------------|--------------|
| `CONTRIBUTE_TO` | yes | sometimes |
| `AFFECT` | no | always null |
| `CORRELATE` | no | always null |
| `which X is most profitable` | no | null |

### Profit / profitability / profitable mapping summary

| User term | Maps to profit_margin? | Mechanism |
|-----------|------------------------|-----------|
| `profit` | **Sometimes** (score 0.675) | 1/2 token overlap with `profit margin` |
| `profit margin` | **Yes** (score 0.90) | Substring match |
| `profitability` | **No** | 0 token overlap; no synonym |
| `profitable` | **No** | 0 token overlap; no synonym |

---

## Proposed Dataset-Agnostic Metric Resolution Strategy

**Goal:** Replace taxi dictionary + fragile token rules with schema-first resolution that works for any registered dataset. No SQL changes in this phase.

### 1. Schema metadata layer (from registry)

Extend `SemanticCatalogEntry` (or parallel `MetricMetadata`) with fields populated by `SchemaProfiler` / governance:

```text
columnName, label, aggregation, unit, semanticTags[], aliases[]
```

- **semanticTags:** auto-derived from column name tokens (`profit`, `margin`, `revenue`, `cost`, `emission`, …) using `SchemaProfiler` keyword lists (already exists for classification).
- **aliases:** curated + generated variants: `profit_margin` → [`profit`, `profitability`, `profitable`, `margin`, `profit margin`].
- **businessConcept:** optional normalized concept key (`PROFITABILITY`, `REVENUE`, `DOWNTIME`) for cross-column reasoning.

All aliases are **per-tenant from schema**, not hardcoded taxi phrases.

### 2. Multi-signal scorer (replace single-path `bestMetric`)

For each catalog metric, compute weighted score:

| Signal | Weight | Example |
|--------|--------|---------|
| Exact alias match | 1.0 | `"profit margin"` in question |
| Token overlap (stemmed) | 0.7 | Porter/suffix strip: `profitability`→`profit` |
| Substring / n-gram | 0.6 | `profit` ⊂ `profitability` after stem |
| semanticTag overlap | 0.5 | question tokens ∩ column tags |
| Embedding similarity (optional) | 0.4 | phrase↔label cosine if model available |
| rankScore prior | ×0.95^n | keep existing catalog ordering bias |

Return **ranked list**, not only winner. Threshold per signal type, not single 0.35 global.

### 3. Question-structure slot binding

Parse structural slots **before** picking winner:

| Pattern | Slots |
|---------|-------|
| `which X highest Y` | groupBy←X, metric←Y |
| `which X is most Y` | groupBy←X, metric←Y |
| `how does X affect Y` | driver←X, outcome←Y |
| `does X correlate with Y` | metricA←X, metricB←Y |
| `what drives Y` | outcome←Y, driver←open |

Each slot scored independently against **metrics only** (dimensions use parallel dimension scorer). Prevents driver metric winning when outcome slot is intended.

### 4. Role-aware resolution output

Replace single `primaryMetric` with:

```text
ResolvedMetricBinding
  primaryMetric      // ranking/contribution outcome
  secondaryMetric    // correlation/impact pair
  driverMetric       // affect/drive questions
  slotSources        // which regex slot filled each
  candidates         // full ranked list for debug
  confidence
```

`MetricResolutionEngine` validates all bound columns exist in registry; rejects substitution when confidence < threshold.

### 5. Stemming and normalization pipeline

Apply before token overlap:

1. Lowercase, strip punctuation.
2. Suffix rules: `-ility`, `-able`, `-ness` → root (`profitability`→`profit`, `profitable`→`profit`).
3. Compound split: `carbon_emission` ↔ `carbon emission`.
4. Match against alias set **and** humanized column name.

This fixes profit/profitability/profitable without domain-specific code.

### 6. Eliminate taxi fallbacks

Remove `matchFragment("revenue")` / `total_amount` defaults in `pickCatalogMetric()`. When catalog score < threshold:

- Return `MetricResolution.rejected("No metric matched for slot: …")` with candidate list.
- Surface clarification option from ranked near-misses.

`SemanticDictionary` becomes **optional enrichment** loaded from tenant config, not a static NYC list.

### 7. Debug contract (already started)

Expose per question:

- Raw terms / regex slots
- All candidate scores (like `MetricResolutionAuditTest`)
- Winner + reason string
- Near-misses within ε of threshold

---

## Expected outcomes for audit questions (after strategy)

| Question | Today | After proposed strategy |
|----------|-------|-------------------------|
| Highest profit by oil field | profit_margin ✓ (if catalog populated) | Same; robust with alias `profit` |
| Downtime affect profitability | primary=downtime_hours, outcome lost | driver=downtime_hours, outcome=profit_margin |
| Carbon correlate profit margin | primary only | metricA=carbon_emission, metricB=profit_margin |
| What drives profitability | UNRESOLVED → taxi fallback | outcome=profit_margin; driver discovery pass |
| Most profitable facility type | total_amount ✗ | metric=profit_margin, groupBy=facility_type |

---

## Key source files

| Component | Path |
|-----------|------|
| Catalog scoring | `semantics/catalog/CatalogQuestionMatcher.java` |
| Schema hints | `semantics/catalog/SchemaDrivenQuestionResolver.java` |
| Extractor | `semantics/QuestionSemanticExtractor.java` |
| Engine | `semantics/MetricResolutionEngine.java` |
| Taxi dictionary | `semantic/SemanticDictionary.java` |
| Fragment match | `semantic/QueryEntityResolver.java` |
| Catalog build | `semantics/catalog/SemanticCatalogBuilder.java` |
| Profiler keywords | `execution/framework/SchemaProfiler.java` |
| Audit test | `src/test/java/.../diagnostics/MetricResolutionAuditTest.java` |

---

## Related documents

- `docs/analytical-model-architecture-plan.md` — pattern-first investigation model (downstream of metric resolution)
- `docs/planner-architecture-diagnostic-report.md` — pipeline gates and planner overlap
