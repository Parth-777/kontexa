
---

### Component 6: Action Execution Layer

**What it does:** Executes confirmed agent actions across connected systems — Jira, Salesforce, Zendesk, and the Kontexity roadmap.

**Key design decisions:**

- **Write-back is strictly controlled.** The action execution layer has write access to connected systems. This is the most dangerous component. Every write is:
  1. Confirmed by the user
  2. Logged before execution
  3. Logged after execution with the system response
  4. Reversible where possible

- **Reversibility.** Where a system supports it, actions are reversible. A Jira priority change can be undone. A Salesforce note can be flagged as agent-generated. Actions that cannot be reversed are flagged as irreversible in the confirmation step.

- **Idempotency keys.** Every write-back carries an idempotency key. If the same action is attempted twice — due to retry logic or a system error — the second attempt is detected and rejected.

- **Rate limiting.** The action layer respects the rate limits of connected systems. Actions are queued rather than fired simultaneously.

**What NOT to do:**
- Do not allow write-back without an idempotency key.
- Do not execute write-back before the execution log entry is written.
- Do not allow the action layer to be called from anywhere except the orchestrator.
- Do not hide irreversible actions — always surface them explicitly in the confirmation step.

---

### Component 7: Decision Memory Store

**What it does:** Stores the outcome of every agent action — what was detected, what was recommended, what the user did, and what happened next. This is the compounding intelligence layer.

**Key design decisions:**

- **Every decision is stored, regardless of outcome.** A user who declines an agent recommendation is as valuable a data point as one who accepts. The reason for declining — if provided — is especially valuable.

- **Outcome tracking.** Where possible, outcomes are tracked. If the Reprioritisation Agent recommended moving a feature to P1 and the user accepted, did the feature ship? Did the deal close? Did the customer churn? These outcomes are stored alongside the decision.

- **Pattern recognition.** Decision Memory enables pattern queries: "In similar situations (high deal risk + low velocity), what did this team typically do, and what were the outcomes?" This is surfaced as context in future agent recommendations, not as instructions.

- **Tenant isolation.** Decision Memory is strictly per-tenant. Cross-tenant learning is only possible with explicit opt-in and full anonymisation.

**Schema:**

```json
{
  "decision_id": "uuid",
  "tenant_id": "nexora",
  "agent": "reprioritisation_agent",
  "trigger_signals": ["signal_id_1", "signal_id_2"],
  "recommendation": { ... },
  "confidence": 0.87,
  "user_action": "accepted | declined | modified",
  "decline_reason": "timing | strategic hold | signal dispute | null",
  "action_taken": { ... },
  "outcome": { ... },
  "timestamp": "2026-05-14T09:00:00Z"
}
```

**What NOT to do:**
- Do not use Decision Memory to override user judgment. It is context, not instruction.
- Do not store personally identifiable information in decision records.
- Do not allow cross-tenant queries without explicit opt-in and anonymisation.
- Do not delete decision records. They are the institutional memory.

---

## Do's and Don'ts — Summary

### Do's

**Architecture:**
- Use webhooks over polling wherever possible
- Normalise signals into a common schema at ingestion
- Enforce tenant isolation at every layer — ingestion, storage, reasoning, action
- Make every agent action idempotent
- Version every prompt
- Write execution log entries before taking action, not after

**Agent design:**
- Separate evaluate() (read-only reasoning) from act() (write execution)
- Always produce a confidence score with its basis
- Always require user confirmation in V1 before writing to external systems
- Always produce a human-readable explanation for every recommendation
- Always ground LLM reasoning in retrieved signals, not model knowledge

**Trust and safety:**
- Flag irreversible actions explicitly in the confirmation UI
- Provide a clear undo path wherever the system supports it
- Log every agent firing — trigger, reasoning, recommendation, decision, action, outcome
- Surface agent chain dependencies transparently — never hide multi-agent coordination
