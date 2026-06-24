# Metric Resolution — Before / After

Schema-driven metric resolution implemented. SQL planner and investigation architecture unchanged.

Run audit: `mvn test -Dtest=SchemaDrivenMetricResolverAuditTest`

---

## What changed

| Component | Change |
|-----------|--------|
| `MetricAliasGenerator` | Auto-generates aliases from column tokens + generic business synonym clusters |
| `SemanticCatalogEntry` | Carries `aliases` list per metric |
| `CatalogQuestionMatcher` | `rankMetrics()` returns full scored candidate list; stem/prefix matching |
| `QuestionSlotExtractor` | Extracts metric phrases from question patterns (ranking, affect, correlate, drives) |
| `SchemaDrivenMetricResolver` | Slot-aware winner + secondary metric; debug trace; **no taxi fallbacks** |
| `MetricResolution` | Adds `candidates` + `MetricResolutionDebug` |
| `QuestionSemanticExtractor` | Removed `matchFragment("revenue")` / `total_amount` defaults |
| `SchemaDrivenQuestionResolver` | Added `which X is most Y` pattern |

---

## Before / After — Audit Questions

### 1. Which oil field generates the highest profit?

| | Before | After |
|---|--------|-------|
| **Primary** | `profit_margin` (token overlap 0.675) | `profit_margin` (score 0.900) |
| **Secondary** | null | null |
| **isUsable** | true | true |
| **Fallback** | none (worked when catalog populated) | none |
| **Winner match** | token `profit` → 1/2 of `profit margin` | alias `profit` → token_overlap |

---

### 2. How does downtime affect profitability?

| | Before | After |
|---|--------|-------|
| **Primary** | `downtime_hours` (driver won) | **`profit_margin`** (outcome slot) |
| **Secondary** | null | **`downtime_hours`** (driver slot) |
| **isUsable** | true | true |
| **Gap fixed** | `profitability` scored 0.0 vs `profit_margin` | stem_prefix match on alias `profitability` (score 1.05) |

---

### 3. Which facility type is most profitable?

| | Before | After |
|---|--------|-------|
| **Primary** | **`total_amount`** (taxi `revenue` fallback) | **`profit_margin`** |
| **Secondary** | null | null |
| **isUsable** | true (wrong metric) | true |
| **Gap fixed** | `profitable` scored 0.0; missing `which X is most Y` regex | alias `profitable`→`profit`; `WHICH_IS_MOST` regex in schema resolver |

---

### 4. What drives profitability?

| | Before | After |
|---|--------|-------|
| **Primary** | **UNRESOLVED** → `total_amount` fallback | **`profit_margin`** |
| **Secondary** | null | null |
| **isUsable** | **false** (0.30) | **true** (0.98) |
| **Gap fixed** | no phrase extraction for `what drives X` | `WHAT_DRIVES` slot + profitability aliases |

---

### 5. Does carbon emission correlate with profit margin?

| | Before | After |
|---|--------|-------|
| **Primary** | `profit_margin` (0.90) | `profit_margin` (0.90) |
| **Secondary** | null | **`carbon_emission`** |
| **isUsable** | true | true |
| **Gap fixed** | second metric discarded | `CORRELATE` slot binds both metrics |

---

## Debug output (example)

```
[metric-resolution-debug] question=How does downtime affect profitability?
[metric-resolution-debug] extracted_phrases=[profitability, How does downtime affect profitability?, downtime]
[metric-resolution-debug] candidate column=profit_margin score=1.050 accepted=true match=profitability~profitability kind=stem_prefix
[metric-resolution-debug] candidate column=downtime_hours score=0.882 accepted=true match=downtime kind=token_overlap
[metric-resolution-debug] winner=profit_margin score=1.05
[metric-resolution-debug] rejected_below_threshold=[]
[metric-resolution-debug] reason=slot=AFFECT_OUTCOME primaryPhrase="profitability" → profit_margin; secondary=downtime_hours
```

---

## NYC taxi note

Taxi questions still work via **registry catalog** (`total_amount` aliases include `revenue`) and **SemanticDictionary** for dimension phrases when catalog is empty. Metric picking no longer hardcodes `total_amount` when schema resolution fails.
