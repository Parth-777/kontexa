export default function GptPlannerPanel({ planner, runId, sql }) {
  if (!planner || planner.served_path !== 'gpt') return null;

  const valid = planner.validation_valid !== false;
  const confidence = planner.confidence ?? 0;

  return (
    <div className="gpt-planner-panel">
      <div className="gpt-planner-panel__header">
        <span className="gpt-planner-panel__badge">GPT Planner</span>
        <span className={`gpt-planner-panel__status ${valid ? 'ok' : 'warn'}`}>
          {valid ? 'Catalogue-valid plan' : 'Validation issues'}
          {' · '}
          confidence {(confidence).toFixed(2)}
        </span>
      </div>
      <div className="gpt-planner-chips">
        {planner.intent && (
          <span className="gpt-planner-chip">
            <span className="gpt-planner-chip__label">intent</span>
            <span className="gpt-planner-chip__value">{planner.intent}</span>
          </span>
        )}
        {planner.metric && (
          <span className="gpt-planner-chip">
            <span className="gpt-planner-chip__label">metric</span>
            <span className="gpt-planner-chip__value">{planner.metric}</span>
          </span>
        )}
        {planner.dimension && (
          <span className="gpt-planner-chip">
            <span className="gpt-planner-chip__label">dimension</span>
            <span className="gpt-planner-chip__value">{planner.dimension}</span>
          </span>
        )}
        {planner.relationship_variable && (
          <span className="gpt-planner-chip">
            <span className="gpt-planner-chip__label">relationship</span>
            <span className="gpt-planner-chip__value">{planner.relationship_variable}</span>
          </span>
        )}
      </div>
      {!valid && planner.validation_issues?.length > 0 && (
        <p className="gpt-planner-panel__issues">{planner.validation_issues.join('; ')}</p>
      )}
      {runId && (
        <p className="gpt-planner-panel__meta">
          run <code className="shadow-run-id">{runId}</code>
        </p>
      )}
      {sql && (
        <details className="shadow-sql-details">
          <summary>GPT SQL</summary>
          <pre className="shadow-sql-pre">{sql}</pre>
        </details>
      )}
    </div>
  );
}
