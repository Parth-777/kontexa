# Frontend / End-to-End Validation Checklist

Use this checklist after schema-driven planning, AnalysisPlan authority, and contamination
regression protections are in place. Goal: validate real user questions end-to-end without
changing architecture unless a concrete failure is observed.

## Prerequisites

- [ ] Backend running (`./mvnw spring-boot:run` from `backend/`)
- [ ] Frontend running (`npm start` from `frontend/`)
- [ ] At least one client dataset connected in catalogue
- [ ] Automated validation log available: `target/cross-dataset-validation.log`

## Automated validation (112 fresh questions)

```bash
cd backend
./mvnw test -Dtest=CrossDatasetValidationRunnerTest
```

Review:

- `target/cross-dataset-validation.log` — per-question pipeline trace
- Console summary — pass/fail counts by failure class

Questions in `ValidationQuestionBank` are **not** reused from regression tests.

---

## Per-question trace (required fields)

For every question, confirm these seven artifacts are present in the log:

| # | Artifact | What to verify |
|---|----------|----------------|
| 1 | **Question** | Exact user text submitted |
| 2 | **QuestionSemantics** | `primaryMetric`, `dimension`, `intent`, `extractedEntities` |
| 3 | **MetricResolution** | Resolved metric/dimension, not rejected |
| 4 | **Investigation** | `metricKey`, `columnKey`, `executable=true` |
| 5 | **AnalysisPlan** | Authoritative plan: intent, metric, dimension, `executable=true` |
| 6 | **Generated SQL** | Non-empty; uses registry columns only |
| 7 | **Final answer** | Materialized summary (grouped top entry, correlation, or scalar) |

---

## Failure classification

When a scenario fails, assign **one primary class** (first gate in pipeline order):

| Class | Symptom | First check |
|-------|---------|-------------|
| `semantic extraction` | Null/wrong metric or dimension in QuestionSemantics | Stage 2 |
| `metric resolution` | `MetricResolution.rejected` or unusable resolution | Stage 3 |
| `intent detection` | `AnalysisPlan.intent` ≠ expected intent | Stage 5 |
| `dimension selection` | Wrong grouping dimension for breakdown/trend/ranking | Stage 5 |
| `relationship selection` | Missing/wrong `relationshipVariable` on RELATIONSHIP | Stage 3–5 |
| `sql generation` | Empty SQL, exception, or missing plan columns in SQL | Stage 6 |

Do **not** change planners, AnalysisPlan, or SQL templates until a failure is reproduced
and classified.

---

## Frontend manual checklist (QueryPage)

### Client selection

- [ ] Client list loads without error
- [ ] Switching client clears prior result
- [ ] Question input accepts multi-word natural language

### Question submission

- [ ] Submit via button and Enter key
- [ ] Loading state shown during request
- [ ] Error message shown on API failure (network / 4xx / 5xx)

### Response display

- [ ] Answer text or table renders
- [ ] SQL debug panel expandable (if enabled)
- [ ] Result columns match expected metric/dimension labels
- [ ] No taxi/oil columns appearing for unrelated client datasets

### Investigation trace (if ExecutionTracePanel wired)

- [ ] Trace panel opens/collapses
- [ ] Steps show COMPLETED for successful runs
- [ ] Failed steps show FAILED with message

---

## Dataset coverage (8 schema-only clients)

Run manual spot-checks (3–5 questions each) in the UI in addition to automated run:

| Dataset | Sample question style |
|---------|----------------------|
| `facility_operations` | "What shift label shows the most defects?" |
| `subscription_events` | "Show payment totals broken down by billing region" |
| `weather_observations` | "Is wind speed linked to rainfall at each station?" |
| `semiconductor_yield` | "Rank fab lines by lithography yield" |
| `hospital_bed_flow` | "Average treatment cost by care unit" |
| `esports_matches` | "Prize payout share by team region" |
| `satellite_telemetry` | "Signal strength over telemetry months" |
| `vineyard_production` | "Harvest volume by grape variety" |

---

## Contamination checks (regression gate)

Before E2E sign-off, confirm contamination suite is green:

```bash
./mvnw test -Dtest=SchemaOnlyClientDatasetRegressionTest
```

Expected: **96/96 pass**, no `pickup_hour` / `pickup_zone` in non-taxi datasets.

---

## Sign-off criteria

- [ ] Automated validation: ≥100 fresh questions executed
- [ ] Failure rate documented by class
- [ ] No unresolved `semantic extraction` contamination on schema-backed datasets
- [ ] Frontend manual checklist complete for at least 2 datasets
- [ ] No architecture changes made unless tied to a classified failure
