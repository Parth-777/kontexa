        Execute agent →
          Collect recommendation →
            Deliver to user →
              Await confirmation →
                Execute action →
                  Write to execution log
```

**What NOT to do:**
- Do not allow agents to call each other directly. All coordination goes through the orchestrator.
- Do not allow two agents to act on the same entity simultaneously.
- Do not allow execution log entries to be modified or deleted.

---

### Component 4: Agent Core

**What it does:** Each agent is a discrete, independent process. It receives a trigger context, reasons across signals, produces a confidence-scored recommendation, and — on confirmation — executes a specific action.

**Standard agent interface:**

```python
class KontexityAgent:
    
    def evaluate(self, trigger_context: TriggerContext) -> AgentRecommendation:
        """
        Receives trigger context.
        Reasons across signals.
        Returns a confidence-scored recommendation.
        Never modifies external systems.
        """
        pass
    
    def act(self, recommendation: AgentRecommendation, 
            confirmation: UserConfirmation) -> AgentAction:
        """
        Called only after explicit user confirmation.
        Executes the recommended action.
        Returns a detailed action record for the execution log.
        """
        pass
    
    def explain(self, action: AgentAction) -> AgentExplanation:
        """
        Produces a human-readable explanation of:
        - What was detected
        - Why it was significant
        - What was recommended
        - What was confirmed
        - What was done
        - Which signals supported each step
        """
        pass
```

**What NOT to do:**
- Do not put business logic in the orchestrator. Business logic belongs in the agent.
- Do not let agents modify external systems in the `evaluate()` step. `evaluate()` is read-only.
- Do not let agents skip the `explain()` step. Every action must be explainable.

---

### Component 5: LLM Reasoning Layer

**What it does:** Agents use LLMs to reason across unstructured signal data — customer feedback, deal notes, market signals, email threads. The LLM layer converts unstructured input into structured reasoning that the agent can act on.

**Key design decisions:**

- **LLMs for reasoning, not for facts.** LLMs interpret and connect signals. They do not generate facts. All factual claims in an agent recommendation must be grounded in retrieved signal data, not LLM knowledge.

- **Retrieval-augmented reasoning.** Before calling the LLM, retrieve the relevant signals from the signal store. Pass them as context. The LLM reasons over the retrieved context, not over its training data.

```
Agent needs to reason about customer signal →
  Retrieve relevant signals from signal store →
    Construct prompt with signal context →
      LLM reasons over context →
        Extract structured reasoning output →
          Validate against source signals →
            Include in recommendation
```

- **Prompt versioning.** Every prompt is versioned. If agent behaviour changes unexpectedly, the prompt version in the execution log tells you exactly which prompt produced the output.

- **Confidence calibration.** The LLM's confidence in its reasoning is one input to the overall confidence score — not the only input. Signal source count and recency are weighted equally.

- **Hallucination prevention.** Every LLM output that makes a factual claim is checked against the retrieved signals. If the LLM claims "customer X mentioned onboarding friction" and that claim cannot be grounded in a retrieved signal, it is dropped.

**What NOT to do:**
- Do not let the LLM generate signal data. It retrieves and reasons, it does not invent.
- Do not use a single prompt for all agents. Each agent has its own prompt, versioned independently.
- Do not trust LLM confidence scores as the sole confidence signal.
- Do not skip hallucination checking. A single incorrect factual claim destroys user trust.
