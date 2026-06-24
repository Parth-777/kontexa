import { useEffect, useState } from 'react';
import './ExecutionTracePanel.css';

const STATUS_ICON = {
  PENDING: '○',
  RUNNING: '◐',
  COMPLETED: '✓',
  FAILED: '✕',
};

/**
 * Collapsible analyst reasoning trace — Basedash-style investigation steps.
 */
export default function ExecutionTracePanel({ trace, animate = true, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen);
  const [visibleCount, setVisibleCount] = useState(0);

  const steps = trace?.steps || [];

  useEffect(() => {
    if (!animate || !open || steps.length === 0) {
      setVisibleCount(steps.length);
      return undefined;
    }
    setVisibleCount(0);
    let i = 0;
    const timer = setInterval(() => {
      i += 1;
      setVisibleCount(i);
      if (i >= steps.length) clearInterval(timer);
    }, 120);
    return () => clearInterval(timer);
  }, [trace, animate, open, steps.length]);

  if (!steps.length) return null;

  const completed = steps.filter((s) => s.status === 'COMPLETED').length;
  const totalMs = steps.reduce((sum, s) => sum + (s.duration_ms || 0), 0);

  return (
    <div className="et-panel">
      <button
        type="button"
        className="et-toggle"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        <span className="et-toggle-label">Investigation trace</span>
        <span className="et-toggle-meta">
          {completed}/{steps.length} steps
          {totalMs > 0 ? ` · ${totalMs}ms` : ''}
        </span>
        <span className="et-chevron">{open ? '▾' : '▸'}</span>
      </button>

      {open && (
        <ol className="et-steps">
          {steps.slice(0, visibleCount || steps.length).map((step, idx) => (
            <li
              key={step.step_key || idx}
              className={`et-step et-step--${(step.status || 'PENDING').toLowerCase()}`}
              style={{ animationDelay: `${idx * 60}ms` }}
            >
              <span className="et-step-icon">{STATUS_ICON[step.status] || '○'}</span>
              <div className="et-step-body">
                <span className="et-step-title">{step.title}</span>
                {step.details?.message && (
                  <span className="et-step-detail">{step.details.message}</span>
                )}
                {step.details?.row_count != null && (
                  <span className="et-step-detail">{step.details.row_count} rows returned</span>
                )}
                {step.details?.chart_type && step.details.chart_type !== 'none' && (
                  <span className="et-step-detail">Chart: {step.details.chart_type}</span>
                )}
                {step.duration_ms > 0 && (
                  <span className="et-step-timing">{step.duration_ms}ms</span>
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
