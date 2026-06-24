# Executive Insight Model

Companion to `docs/executive_insight_quality_848f4ae5.plan.md` — implemented pipeline.

## Flow

```
Catalogue approval → TenantBusinessProfile
Refresh Insights:
  SignalDetection → SIGNALS CollectedData
  Per table: agents + ExecutiveMetricPack (EXEC:*)
  Lens agents → InsightCandidates
  MaterialityRanker → top 3
  ExecutiveNarrator → cards
  InsightEvidenceValidator → persist
```

## Components

| Class | Role |
|-------|------|
| `ExecutiveMetricPack` | MoM delta, contribution top-3, concentration |
| `SignalSummaryBuilder` | Wires `SignalDetectionService` into collected data |
| `GrowthLensAgent` / `RiskLensAgent` / `EfficiencyLensAgent` / `CustomerLensAgent` | Structured candidates per business lens |
| `MaterialityRanker` | Scores and keeps top 3 (max 4 on high-alert weeks) |
| `ExecutiveVoice` | Shared SVP persona, metric humanization, $/% formatting |
| `ExecutiveNarrator` | C-suite narration + LLM Leadership Brief (not raw claims) |
| `MeetingReadyInsightPolisher` | Final pass: slide titles, So What, badges, actionable strategies |
| `InsightEvidenceValidator` | Numbers, labels, trend grounding |
| `TenantBusinessProfileService` | Industry / north-star context at approval |

## Migrations

Run in pgAdmin on `admindb`:

- `src/main/resources/tenant_business_profile_migration.sql`

## API

- `POST /api/agent/dashboard` — response includes `dailyBrief`
- `PATCH /api/agent/insights/{id}/status` — optional `dismissReason` in body
