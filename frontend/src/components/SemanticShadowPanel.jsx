import { useState } from 'react';

function PlanChip({ label, value }) {
  if (value == null || value === '') return null;
  return (
    <span className="shadow-plan-chip">
      <span className="shadow-plan-chip__label">{label}</span>
      <span className="shadow-plan-chip__value">{String(value)}</span>
    </span>
  );
}

function PlanSummary({ title, plan }) {
  if (!plan || Object.keys(plan).length === 0) {
    return (
      <div className="shadow-plan-card shadow-plan-card--empty">
        <h4>{title}</h4>
        <p className="shadow-plan-empty">No plan</p>
      </div>
    );
  }
  return (
    <div className="shadow-plan-card">
      <h4>{title}</h4>
      <div className="shadow-plan-chips">
        <PlanChip label="intent" value={plan.intent} />
        <PlanChip label="metric" value={plan.primaryMetric || plan.metric} />
        <PlanChip label="dimension" value={plan.dimension || (plan.dimensions || [])[0]} />
        <PlanChip label="relationship" value={plan.relationshipVariable || plan.relationshipVariable} />
        <PlanChip label="executable" value={plan.executable != null ? String(plan.executable) : null} />
      </div>
      {plan.blockingReasons?.length > 0 && (
        <p className="shadow-plan-blocked">{plan.blockingReasons.join('; ')}</p>
      )}
    </div>
  );
}

function ExecutionBlock({ title, execution, sql }) {
  const entry = execution?.[0];
  const sqlText = sql?.[0]?.sql || '';
  return (
    <div className="shadow-exec-card">
      <h4>{title}</h4>
      <p className="shadow-exec-meta">
        Rows: <strong>{entry?.rowCount ?? 0}</strong>
        {entry?.elapsedMs != null && (
          <> · {entry.elapsedMs}ms</>
        )}
      </p>
      {sqlText && (
        <details className="shadow-sql-details">
          <summary>SQL</summary>
          <pre className="shadow-sql-pre">{sqlText}</pre>
        </details>
      )}
      {entry?.sampleRows?.length > 0 && (
        <details className="shadow-sql-details">
          <summary>Sample rows ({entry.sampleRows.length})</summary>
          <pre className="shadow-sql-pre">{JSON.stringify(entry.sampleRows, null, 2)}</pre>
        </details>
      )}
    </div>
  );
}

export default function SemanticShadowPanel({ shadow, runId }) {
  const [open, setOpen] = useState(true);
  if (!shadow) return null;

  const div = shadow.divergence || {};
  const valid = shadow.gpt_validation_valid;
  const summary = div.summary || 'Comparison available';

  return (
    <div className="semantic-shadow-panel">
      <button
        type="button"
        className="semantic-shadow-panel__toggle"
        onClick={() => setOpen((v) => !v)}
      >
        <span className="semantic-shadow-panel__badge">Shadow</span>
        <span className="semantic-shadow-panel__title">Legacy vs GPT planner</span>
        <span className={`semantic-shadow-panel__status ${valid ? 'ok' : 'warn'}`}>
          GPT {valid ? 'valid' : 'invalid'} · {summary}
        </span>
        <span className="semantic-shadow-panel__chevron">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <div className="semantic-shadow-panel__body">
          <p className="semantic-shadow-served">
            Served to you: <strong>{shadow.served_path || 'legacy'}</strong>
            {' · '}
            GPT confidence: {(shadow.gpt_confidence ?? 0).toFixed(2)}
            {runId && (
              <>
                {' · '}
                run <code className="shadow-run-id">{runId}</code>
              </>
            )}
          </p>

          {!valid && shadow.gpt_validation_issues?.length > 0 && (
            <p className="shadow-validation-issues">
              Validation: {shadow.gpt_validation_issues.join('; ')}
            </p>
          )}

          <div className="shadow-divergence-row">
            <span className={div.intent_match ? 'shadow-match' : 'shadow-mismatch'}>
              intent {div.intent_match ? '✓' : '✗'}
            </span>
            <span className={div.metric_match ? 'shadow-match' : 'shadow-mismatch'}>
              metric {div.metric_match ? '✓' : '✗'}
            </span>
            <span className={div.dimension_match ? 'shadow-match' : 'shadow-mismatch'}>
              dimension {div.dimension_match ? '✓' : '✗'}
            </span>
            <span className="shadow-rows">
              rows {div.legacy_row_count ?? 0} → {div.gpt_row_count ?? 0}
            </span>
          </div>

          <div className="shadow-plan-grid">
            <PlanSummary title="Legacy analysis plan" plan={shadow.legacy_analysis_plan} />
            <PlanSummary title="GPT analysis plan" plan={shadow.gpt_analysis_plan} />
          </div>

          <details className="shadow-sql-details">
            <summary>GPT structured plan (JSON)</summary>
            <pre className="shadow-sql-pre">
              {JSON.stringify(shadow.gpt_structured_plan, null, 2)}
            </pre>
          </details>

          <div className="shadow-exec-grid">
            <ExecutionBlock
              title="Legacy execution"
              execution={shadow.legacy_execution}
              sql={shadow.legacy_sql}
            />
            <ExecutionBlock
              title="GPT execution"
              execution={shadow.gpt_execution}
              sql={shadow.gpt_sql}
            />
          </div>

          {shadow.error && (
            <p className="shadow-plan-blocked">GPT error: {shadow.error}</p>
          )}
        </div>
      )}
    </div>
  );
}
