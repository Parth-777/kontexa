# Kontexity Agentic Architecture
### Engineering Spec v1.0 · May 2026

---

## Overview

Every Kontexity agent follows one loop:

```
Detect → Inform → Decide → Act → Explain
```

- **Detect:** Monitor signals for meaningful change
- **Inform:** Surface the change to the right person with context
- **Decide:** Present a recommended action and wait for confirmation
- **Act:** Execute the action across connected systems
- **Explain:** Log what was done, why, and what signals triggered it

No agent skips a step. No agent acts without explanation. No agent acts without confirmation in V1.

---

## Core Architectural Principles

### 1. Signal-First, Not Schedule-First

Agents fire on signal change, not on a timer. A velocity drop detected at 3pm on a Tuesday should trigger the Risk Detection Agent immediately — not at the next scheduled run. The system is event-driven, not cron-driven.

**Why this matters:** A scheduled report is a SaaS 2.0 pattern. An agent that fires when something changes is an AI-native pattern. The difference is whether the system is reactive or proactive.

### 2. Confidence Before Action

Every agent action carries a confidence score before it is presented to the user. Confidence is calculated from:

- **Source count:** How many independent signal sources support the trigger
- **Recency:** How fresh are the signals
- **Consistency:** Are the signals pointing in the same direction or conflicting

An agent with low confidence presents its finding as an observation, not a recommendation. An agent with high confidence presents a specific recommended action.

```
Low confidence  → "I noticed X. This may be worth attention."
Medium confidence → "X suggests Y. Here is what I recommend."
High confidence  → "X, Y, and Z are converging. I recommend this specific action."
```

### 3. Human-in-the-Loop Always (V1)

In V1, no agent takes an irreversible action without explicit user confirmation. Every write-back to Jira, Salesforce, or any other system requires a confirmation step.

The confirmation is:
- What the agent is about to do — stated specifically
- Why — the signals that triggered it
- What happens if confirmed
- What happens if declined

This is non-negotiable in V1. Trust is earned before autonomy is granted.

### 4. Traceability is Non-Negotiable

Every agent output — whether an observation, a recommendation, or a completed action — must link back to:
- The specific signal(s) that triggered it
- The source of each signal
- The timestamp of detection
- The confidence score and its basis
- The reasoning chain that connected signals to action

This is not a logging feature. It is the primary trust mechanism. A CPO who cannot verify why an agent fired will stop using it.

### 5. Agents Do Not Chain Without Transparency

Agents can trigger other agents — but only with explicit logging of the chain. If the Reprioritisation Agent fires because the Signal Escalation Agent created an opportunity, the user sees the full chain:

```
Signal Escalation Agent detected pattern →
  Created opportunity →
    Reprioritisation Agent evaluated opportunity →
      Recommended priority change →
        User confirmed →
          Jira updated
```

Hidden agent chains are how AI systems become black boxes. Every step in every chain is logged and visible.

### 6. Idempotency

Every agent action must be idempotent — running it twice produces the same result as running it once. If the Reprioritisation Agent fires, confirms a priority change, and then fires again on the same signal, the second run detects that the action has already been taken and does nothing.

This prevents double-actions from duplicate signal events, retry logic, or system restarts.

### 7. Graceful Degradation

