
### Don'ts

**Architecture:**
- Don't let agents communicate directly with each other — all coordination through the orchestrator
- Don't allow write-back from the evaluate() step
- Don't use absolute signal thresholds — always baseline-relative
- Don't allow the same agent to act on the same entity twice within a deduplication window
- Don't share signal stores, decision memory, or execution logs across tenants

**Agent design:**
- Don't let the LLM generate signal data — it retrieves and reasons, not invents
- Don't skip hallucination checking on factual claims
- Don't use a single prompt across all agents
- Don't skip the explain() step — every action must be explainable
- Don't allow agents to fire on stale data — check signal readiness before evaluation

**Trust:**
- Don't present low-confidence findings as recommendations
- Don't allow autonomous action without confirmation in V1
- Don't delete execution log entries
- Don't allow Decision Memory to instruct — only to inform
- Don't build agent chains that are invisible to the user

---

## Agent Execution States

Every agent instance moves through defined states. No state transitions are skipped.

```
IDLE
  ↓ (trigger event received)
EVALUATING
  ↓ (recommendation produced)
AWAITING_CONFIRMATION
  ↓ (user confirms)         ↓ (user declines)        ↓ (user modifies)
ACTING                   DECLINED                  MODIFIED → ACTING
  ↓                          ↓                          ↓
COMPLETED                 LOGGED                    COMPLETED
  ↓
LOGGED
```

Timeout handling: If an agent stays in AWAITING_CONFIRMATION for more than 48 hours with no user response, it transitions to EXPIRED and is logged. The trigger is re-evaluated on the next signal update.

---

## Signal Readiness Gate

No agent fires when signal readiness is below threshold. The Signal Readiness Agent runs continuously and maintains a per-tenant score across four dimensions:

| Dimension | What it measures | Weight |
|---|---|---|
| Coverage | Are all required sources connected? | 30% |
| Freshness | Last sync < 48 hours? | 30% |
| Volume | Sufficient signal density? | 20% |
| Consistency | Are sources corroborating each other? | 20% |

**Threshold:** Agents that require cross-domain signals require a minimum readiness score of 70. Single-domain agents require 50. Below threshold, the agent surfaces a readiness warning instead of a recommendation.

---

## V1 Scope — What to Build First

The full architecture above is the target. V1 should implement:

**Must have in V1:**
- Signal ingestion for Jira, Salesforce, Zendesk
- Change detection engine with baseline-relative thresholds
- Orchestrator with conflict detection and execution log
- Reprioritisation Agent (highest value, most demonstrable)
- Signal Escalation Agent (feeds Reprioritisation)
- Risk Detection Agent (Jira velocity and blockers)
- Brief Agent (runs daily, feeds CPO brief)
- Signal Readiness Agent (gates all other agents)
- Traceability for every agent output
- Human confirmation step for all write-backs
- Decision Memory logging (storage only, no pattern queries yet)

**Defer to V2:**
- Decision Memory pattern queries and surfacing
- Churn Risk Agent
- Competitive Move Agent
- Alignment Agent
- Cross-agent chain visualisation
- Autonomous actions (no confirmation required)

---

*Kontexity Agentic Architecture Spec v1.0 · May 2026*
*For internal engineering use · Context Systems Private Limited*
