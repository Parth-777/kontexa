# 2M Row Dataset — What Kontexa Does

When your warehouse table has **~2,000,000 rows**, Kontexa classifies it as **LARGE** (threshold: 1M rows) and applies the scale pipeline below.

## Strategy (implemented)

**Principle:** Compute in the warehouse, ship **aggregates** to the LLM — never millions of raw rows.

| Layer | Behavior for 2M rows |
|--------|----------------------|
| **Tier** | `LARGE` from catalogue `rowCount` |
| **Window** | Last **90 days** on the date column (from semantic `dataMax` when available) |
| **Sampling** | **No** `SELECT *` sample — `TableProfileService` (COUNT, MIN/MAX, monthly volume) |
| **Metrics** | Max **2** numeric KPIs, **1** dimension (highest semantic score) |
| **SQL guard** | No `SELECT *`, requires `LIMIT` or `GROUP BY`, injects date window |
| **BigQuery** | Dry-run byte cap per query (5GB) and per run (50GB) |
| **Results** | Max **500** rows returned per query to JVM |
| **Budget** | Max **40** warehouse queries per Refresh Insights run |
| **Anomaly** | Monthly buckets (~24 points), not row-level stats |
| **Root cause** | No open-ended ReAct — **template** drill-down (1 guarded `GROUP BY` query) |
| **Rollups** | Pre-aggregate daily metrics in Postgres; **TrendAgent** reads rollups when fresh |
| **Chat** | LLM SQL capped at 500 rows; `SELECT *` blocked on large catalogues |
| **Readiness** | Uses catalogue `rowCount` — no full-table `COUNT(*)` |

## Refresh Insights flow (2M-row table)

1. Readiness check (connectivity + catalogue `rowCount` > 0).
2. Classify table → `LARGE`, build 90-day window.
3. Skip if no date column (required for LARGE).
4. Profile + executive metrics + trend/KPI/distribution/anomaly (all windowed/guarded).
5. Template root-cause card if anomaly ≥ 15% move.
6. Lens agents → top 3 insights → meeting-ready polish.
7. Persist run stats to `agent_runs`.

## Migrations to run (pgAdmin on `admindb`)

```sql
-- Required
\i agent_runs_migration.sql

-- Required when rollups enabled (default ON in application.properties)
\i rollup_migration.sql
```

Re-**approve catalogue** after connecting 2M-row data so `rowCount` and semantic enricher are current. Rollups build on approval and nightly.

## Configuration

See `src/main/resources/application.properties` — `kontexa.scale.*` keys.

To disable rollups (more BQ cost, still safe):

```properties
kontexa.scale.rollup.enabled=false
```

## Verify in logs

After Refresh Insights:

```
[Orchestrator] Table your_table tier=LARGE rowCount=2000000 metrics=2 dims=1
```

You should **not** see `Sample rows from your_table`.
