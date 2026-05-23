If a signal source is unavailable — Jira is down, Salesforce sync failed, a webhook is broken — the agent degrades gracefully:
- It does not fire on stale data
- It surfaces a signal readiness warning instead
- It resumes automatically when the source reconnects
- It never presents confident recommendations based on incomplete data

---

## Architecture Components

### Component 1: Signal Ingestion Layer

**What it does:** Collects raw signals from all connected sources, normalises them into a common format, and stores them in the signal store.

**Key design decisions:**

```
Source → Webhook/Polling → Normaliser → Signal Store → Change Detector
```

- **Webhooks preferred over polling.** Jira, Salesforce, and Zendesk all support webhooks. Use them. Polling introduces latency and creates unnecessary load.
- **Normalised signal schema.** Every signal — regardless of source — is stored in a common format:

```json
{
  "signal_id": "uuid",
  "source": "jira",
  "source_entity_id": "EPIC-123",
  "signal_type": "velocity_drop",
  "value": -0.28,
  "baseline": 0.85,
  "timestamp": "2026-05-14T09:00:00Z",
  "tenant_id": "nexora",
  "raw_payload": { ... }
}
```

- **Tenant isolation is enforced at the ingestion layer.** Signal data from one tenant never touches another tenant's data store. This is a hard boundary, not a soft filter.

**What NOT to do:**
- Do not store raw payloads as the primary record. Normalise first, store raw as a reference.
- Do not process signals synchronously in the webhook handler. Acknowledge immediately, process asynchronously.
- Do not share signal stores across tenants under any circumstances.

---

### Component 2: Change Detection Engine

**What it does:** Monitors the signal store for meaningful changes. Determines when a change is significant enough to wake an agent.

**Key design decisions:**

- **Delta-based, not state-based.** The engine compares the current signal value against a rolling baseline. A velocity of 60% is only significant if the baseline was 85%. Raw values without baselines are meaningless.

- **Significance thresholds are configurable per tenant.** A Series B company with 3 engineers has different significance thresholds than a Series D company with 50 engineers.

- **Deduplication window.** If the same signal fires multiple times within a configurable window (default: 4 hours), only the first firing wakes the agent. This prevents alert storms from noisy sources.

```
Signal change detected →
  Calculate delta from baseline →
    Apply significance threshold →
      Check deduplication window →
        If significant and not duplicate: emit AgentTriggerEvent
        Else: log and discard
```

**What NOT to do:**
- Do not wake agents on every signal update. Only meaningful changes warrant attention.
- Do not use absolute thresholds. A 20% drop means different things in different contexts.
- Do not ignore deduplication. Noisy signals will overwhelm users and destroy trust.

---

### Component 3: Agent Orchestrator

**What it does:** Receives AgentTriggerEvents, routes them to the correct agent(s), manages agent execution, prevents conflicting actions, and maintains the execution log.

**Key design decisions:**

- **One orchestrator, many agents.** The orchestrator is the single point of coordination. Agents do not communicate directly with each other — they communicate through the orchestrator.

- **Priority queue.** Trigger events are queued by urgency. A delivery commitment at risk for a $200K deal is higher priority than a velocity drop with no deal attached.

- **Conflict detection.** Before executing an agent action, the orchestrator checks whether another agent has already acted on the same entity in the last N hours. If so, it surfaces the conflict rather than allowing two agents to take contradictory actions on the same Jira epic.

- **Execution log.** Every agent execution — trigger, reasoning, recommendation, user decision, action taken — is written to an immutable log.

```
AgentTriggerEvent →
  Orchestrator receives →
    Route to correct agent(s) →
      Conflict check →
