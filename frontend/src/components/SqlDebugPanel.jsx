/**
 * Developer-mode SQL execution inspector — shows real warehouse attempts and repairs.
 */
export default function SqlDebugPanel({ panel }) {
  if (!panel) return null;

  const sqlEntries = panel.generated_sql || [];
  const repairs = panel.repair_attempts || [];
  const grouped = panel.grouped_results || [];

  return (
    <details className="sql-debug-panel">
      <summary className="sql-debug-panel__summary">SQL debug ({repairs.length} attempts)</summary>
      <div className="sql-debug-panel__body">
        {sqlEntries.map((s) => (
          <div key={s.key} className={`sql-debug-entry${s.success ? ' sql-debug-entry--ok' : ''}`}>
            <div className="sql-debug-entry__header">
              <strong>{s.key}</strong>
              <span>{s.strategy || 'primary'}</span>
              <span>{s.row_count} rows</span>
              <span>{s.elapsed_ms}ms</span>
            </div>
            {s.failure_reason && <p className="sql-debug-entry__error">{s.failure_reason}</p>}
            {s.group_by_columns?.length > 0 && (
              <p className="sql-debug-entry__meta">GROUP BY: {s.group_by_columns.join(', ')}</p>
            )}
            <pre className="sql-debug-entry__sql">{s.sql}</pre>
          </div>
        ))}

        {repairs.length > 0 && (
          <div className="sql-debug-repairs">
            <p className="sql-debug-repairs__title">Repair attempts</p>
            {repairs.map((r, i) => (
              <div key={`${r.query_key}-${r.attempt_index}-${i}`} className="sql-debug-repair">
                <span>#{r.attempt_index}</span>
                <span>{r.strategy}</span>
                <span>{r.row_count} rows</span>
                <span className={r.success ? 'ok' : 'fail'}>{r.success ? 'ok' : r.failure_reason}</span>
              </div>
            ))}
          </div>
        )}

        {grouped.length > 0 && (
          <div className="sql-debug-grouped">
            <p className="sql-debug-grouped__title">Grouped results ({grouped.length})</p>
            {grouped.slice(0, 8).map((g) => (
              <div key={g.rank} className="sql-debug-grouped__row">
                <span>{g.segment}</span>
                <span>{g.value?.toFixed?.(2) ?? g.value}</span>
                <span>{g.share_pct?.toFixed?.(1)}%</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </details>
  );
}
