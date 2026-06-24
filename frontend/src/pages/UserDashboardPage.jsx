import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  askQuestion,
  fetchTablesForSchema,
  getAgentCharts,
  getFollowUpQuestions,
  getPersistedInsights,
  runAgentDashboard,
  runDecisionAnalysis,
  fetchPlannerStatus,
  updateInsightStatus,
} from '../api/queryApi';
import ChartRenderer from '../components/ChartRenderer';
import CorrelationAnalysisCard from '../components/CorrelationAnalysisCard';
import ExecutionTracePanel from '../components/ExecutionTracePanel';
import GptPlannerPanel from '../components/GptPlannerPanel';
import SemanticShadowPanel from '../components/SemanticShadowPanel';
import SqlDebugPanel from '../components/SqlDebugPanel';
import TableRenderer from '../components/TableRenderer';
import { sanitizeDisplayText } from '../utils/presentationSanitizer';
import { clearSession, getSession } from '../api/session';
import './UserDashboardPage.css';

/**
 * Converts a persisted InsightCardEntity (from GET /api/agent/insights) into
 * the same shape as a live InsightCard (from POST /api/agent/dashboard).
 * The entity stores reasons/strategies/metricHighlights as JSON strings.
 */
function normalizePersistedCard(entity) {
  const parse = (v) => {
    if (!v) return [];
    try { return JSON.parse(v); } catch { return []; }
  };
  const parseChart = (raw) => {
    if (!raw || raw === 'null' || raw === '[]') return null;
    try {
      const chart = JSON.parse(raw);
      return chart?.type && chart?.data ? chart : null;
    } catch {
      return null;
    }
  };
  return {
    id:               entity.id,
    title:            entity.title,
    description:      entity.description,
    impactLevel:      entity.impactLevel,
    badge:            entity.badge,
    agentName:        entity.agentName,
    confidence:       entity.confidence,
    metricHighlights: parse(entity.metricHighlights),
    reasons:          parse(entity.reasons),
    strategies:       parse(entity.strategies),
    sourceColumns:    parse(entity.sourceColumns),
    chart:            parseChart(entity.chartSpec),
    generatedAt:      entity.generatedAt,
    status:           entity.status,
    persisted:        true,
  };
}

/** Resolve chart panel entry for an insight card, if one exists. */
function resolveInsightChart(card, chartItems) {
  if (card?.chart?.type && Array.isArray(card.chart.data) && card.chart.data.length > 0) {
    return {
      insightId: card.id,
      title: card.title,
      badge: card.badge,
      chart: card.chart,
    };
  }
  if (card?.id && chartItems?.length) {
    const match = chartItems.find((c) => c.insightId === card.id);
    if (match?.chart) return match;
  }
  return null;
}

function hasInsightChart(card, chartItems) {
  return resolveInsightChart(card, chartItems) != null;
}

/* ─── Badge config ─────────────────────────────────────────── */
const BADGE_CONFIG = {
  ALERT:       { label: 'Alert',       cls: 'badge--alert',       icon: '🔴' },
  RISK:        { label: 'Risk',        cls: 'badge--risk',        icon: '⚠️' },
  OPPORTUNITY: { label: 'Opportunity', cls: 'badge--opportunity', icon: '✅' },
  INFO:        { label: 'Info',        cls: 'badge--info',        icon: 'ℹ️'  },
  HIGH:        { label: 'High',        cls: 'badge--alert',       icon: '🔴' },
  MEDIUM:      { label: 'Medium',      cls: 'badge--risk',        icon: '⚠️'  },
  LOW:         { label: 'Low',         cls: 'badge--info',        icon: 'ℹ️'  },
  POSITIVE:    { label: 'Positive',    cls: 'badge--opportunity', icon: '✅'  },
};

function getBadge(card) {
  return BADGE_CONFIG[card.badge] || BADGE_CONFIG[card.impactLevel] || BADGE_CONFIG.INFO;
}

const IMPACT_ORDER = { HIGH: 0, MEDIUM: 1, POSITIVE: 2, LOW: 3, INFO: 4 };

function sortByImpact(cards) {
  return [...cards].sort((a, b) => {
    const badgeScore = (c) => {
      if (c.badge === 'ALERT') return 0;
      if (c.badge === 'RISK') return 1;
      if (c.badge === 'OPPORTUNITY') return 2;
      return 5;
    };
    const diff = badgeScore(a) - badgeScore(b);
    if (diff !== 0) return diff;
    return (IMPACT_ORDER[a.impactLevel] ?? 9) - (IMPACT_ORDER[b.impactLevel] ?? 9);
  });
}

/** Stable key for hiding cards/anomalies from the feed */
function feedKey(item) {
  if (item.id) return item.id;
  if (item.metric) return `anomaly:${item.metric}`;
  return item.title;
}

function ChartsPanel({ loading, error, charts, focusedOnly, onShowAll }) {
  return (
    <aside className="inbox-charts">
      <div className="charts-header">
        <span className="charts-title">{focusedOnly ? 'Insight chart' : 'Charts'}</span>
        <span className="charts-sub">{charts?.length || 0}</span>
      </div>
      {focusedOnly && onShowAll ? (
        <button type="button" className="charts-show-all-btn" onClick={onShowAll}>
          ← Show all charts
        </button>
      ) : null}
      <div className="charts-body">
        {loading ? <p className="charts-state">Loading charts…</p> : null}
        {error && !loading ? <p className="charts-state charts-state--error">⚠️ {error}</p> : null}
        {!loading && !error && (!charts || charts.length === 0) ? (
          <p className="charts-state">
            No charts yet. Click <strong>Refresh Insights</strong>, then use <strong>View as chart</strong> on an insight card.
          </p>
        ) : null}
        {(charts || []).map((item) => (
          <div className="charts-card" key={item.id || item.insightId || item.title}>
            <div className="charts-card-head">
              <p className="charts-card-title">{item.title}</p>
              {item.badge ? <span className="charts-card-badge">{item.badge}</span> : null}
            </div>
            {item.chart?.title ? <p className="charts-card-subtitle">{item.chart.title}</p> : null}
            <ChartRenderer spec={item.chart} />
          </div>
        ))}
      </div>
    </aside>
  );
}

/* ─── Shared card footer ───────────────────────────────────── */
function CardFooter({ onMarkAsRead, onDismiss, onViewChart, showChart }) {
  return (
    <div className="ic-card-footer">
      <div className="ic-card-footer-left">
        <button className="ic-read-btn" type="button" onClick={onMarkAsRead}>
          ✓ Mark as read
        </button>
        {showChart ? (
          <button className="ic-chart-btn" type="button" onClick={onViewChart}>
            📊 View as chart
          </button>
        ) : null}
      </div>
      <button className="ic-dismiss-btn" type="button" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

/* ─── Insight card ─────────────────────────────────────────── */
function InsightCard({ card, onMarkAsRead, onDismiss, onViewChart, showChart }) {
  const badge = getBadge(card);

  return (
    <div className="ic-card">
      <div className="ic-header">
        <h3 className="ic-title">{card.title}</h3>
        <span className={`ic-badge ${badge.cls}`}>{badge.icon} {badge.label}</span>
      </div>
      <p className="ic-desc">{card.description}</p>

      {card.metricHighlights?.length > 0 && (
        <div className="ic-metrics">
          {card.metricHighlights.map((m, i) => (
            <div className="ic-metric-chip" key={i}>
              <span className="ic-metric-value">{m.value}</span>
              <span className="ic-metric-label">{m.label}</span>
            </div>
          ))}
        </div>
      )}

      {card.reasons?.length > 0 && (
        <div className="ic-reasons-block">
          <p className="ic-block-label">WHY THIS HAPPENED</p>
          <ul className="ic-reason-list">
            {card.reasons.map((r, i) => <li key={i}>{r}</li>)}
          </ul>
        </div>
      )}

      {card.strategies?.length > 0 && (
        <div className="ic-strategies-block">
          <p className="ic-block-label">RECOMMENDED STRATEGIES</p>
          <ul className="ic-strategy-list">
            {card.strategies.map((s, i) => <li key={i}>{s}</li>)}
          </ul>
        </div>
      )}

      <CardFooter
        onMarkAsRead={() => onMarkAsRead(card)}
        onDismiss={() => onDismiss(card)}
        onViewChart={() => onViewChart(card)}
        showChart={showChart}
      />
    </div>
  );
}

/* ─── Anomaly card (same actions as insight cards) ─────────── */
function AnomalyCard({ anomaly, onMarkAsRead, onDismiss }) {
  const isUp = anomaly.direction === 'UP';
  return (
    <div className="ic-card ic-card--anomaly">
      <div className="ic-header">
        <h3 className="ic-title">
          {Math.abs(anomaly.changePercent).toFixed(1)}% {isUp ? 'spike' : 'drop'} in {anomaly.metric}
        </h3>
        <span className={`ic-badge ${isUp ? 'badge--risk' : 'badge--alert'}`}>
          {isUp ? '⚠️ Risk' : '🔴 Alert'}
        </span>
      </div>
      <p className="ic-desc">{anomaly.description}</p>
      <CardFooter
        onMarkAsRead={() => onMarkAsRead(anomaly)}
        onDismiss={() => onDismiss(anomaly)}
      />
    </div>
  );
}

/* ─── Chat message ─────────────────────────────────────────── */
function ChatMessage({ msg, onAsk }) {
  const isUser = msg.role === 'user';
  const isReasoning = msg.type === 'reasoning';
  const isMixed     = msg.type === 'mixed';

  return (
    <div className={`chat-msg ${isUser ? 'chat-msg--user' : 'chat-msg--ai'}`}>
      {!isUser && (
        <span className={`chat-msg-avatar ${isReasoning ? 'chat-msg-avatar--reason' : ''}`}>
          {isReasoning ? '🧠' : 'K'}
        </span>
      )}
      <div className={`chat-msg-bubble ${isReasoning ? 'chat-msg-bubble--reasoning' : ''}`}>

        {/* Mode label */}
        {isReasoning && <p className="chat-msg-mode-label">AI Analysis</p>}
        {isMixed && msg.text && <p className="chat-msg-mode-label">AI Insight</p>}
        {msg.isClarification && <p className="chat-msg-clarify-label">Clarification needed</p>}

        {/* Main text */}
        {msg.text && (
          <p className={`chat-msg-text ${isReasoning ? 'chat-msg-text--reasoning' : ''}`}>
            {msg.text}
          </p>
        )}

        {/* Result table only — SQL runs in backend, not shown in UI */}
        {msg.rows?.length > 0 && (
          <div className="chat-msg-table-wrap">
            <table className="chat-msg-table">
              <thead>
                <tr>{Object.keys(msg.rows[0]).map(k => <th key={k}>{k}</th>)}</tr>
              </thead>
              <tbody>
                {msg.rows.slice(0, 10).map((row, i) => (
                  <tr key={i}>
                    {Object.keys(msg.rows[0]).map(k => <td key={k}>{row[k] ?? '—'}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
            {msg.rows.length > 10 && (
              <p className="chat-msg-more">+{msg.rows.length - 10} more rows</p>
            )}
          </div>
        )}

        {/* Follow-up suggestion chips */}
        {msg.followUpSuggestions?.length > 0 && (
          <div className="chat-followup-chips">
            {msg.followUpSuggestions.map((s, i) => (
              <button key={i} className="chat-followup-chip" type="button"
                onClick={() => onAsk && onAsk(s)}>
                {s}
              </button>
            ))}
          </div>
        )}

        {msg.error && <p className="chat-msg-error">{msg.error}</p>}
        {msg.loading && <span className="chat-typing"><span/><span/><span/></span>}
      </div>
    </div>
  );
}

const ANALYSIS_PROGRESS_STEPS = [
  { step_key: 'understand_question', title: 'Understanding question…', status: 'RUNNING' },
  { step_key: 'detect_intent', title: 'Detecting analytical intent…', status: 'PENDING' },
  { step_key: 'resolve_metric_dimension', title: 'Resolving metric and dimension…', status: 'PENDING' },
  { step_key: 'build_aggregation_plan', title: 'Building aggregation plan…', status: 'PENDING' },
  { step_key: 'execute_warehouse', title: 'Executing warehouse query…', status: 'PENDING' },
  { step_key: 'validate_results', title: 'Validating aggregation consistency…', status: 'PENDING' },
  { step_key: 'generate_visualization', title: 'Creating comparison visualization…', status: 'PENDING' },
  { step_key: 'synthesize_insight', title: 'Summarizing dominant patterns…', status: 'PENDING' },
];

function DecisionAnalysisLoading({ question }) {
  const [stepIndex, setStepIndex] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => {
      setStepIndex((i) => (i < ANALYSIS_PROGRESS_STEPS.length - 1 ? i + 1 : i));
    }, 900);
    return () => clearInterval(timer);
  }, []);

  const steps = ANALYSIS_PROGRESS_STEPS.map((s, i) => ({
    ...s,
    status: i < stepIndex ? 'COMPLETED' : i === stepIndex ? 'RUNNING' : 'PENDING',
  }));

  return (
    <div className="chat-msg chat-msg--ai">
      <span className="chat-msg-avatar chat-msg-avatar--decision">⚡</span>
      <div className="chat-msg-bubble chat-msg-bubble--decision">
        <p className="chat-msg-mode-label">Analysis in progress</p>
        {question && <p className="di-loading-question">{question}</p>}
        <ExecutionTracePanel trace={{ steps }} animate={false} defaultOpen />
        <span className="chat-typing"><span/><span/><span/></span>
      </div>
    </div>
  );
}

/* ─── Decision Intelligence card ──────────────────────────── */
function DecisionInsightMessage({ msg }) {
  const d = msg.decision;
  if (!d) return null;

  const card = d.executive_card || {};
  const debug = d.presentation_debug === true;

  const presentation = d.presentation || null;

  const title = card.title || d.title || d.insight?.title;
  const takeaway = card.key_takeaway || d.key_takeaway || d.executive_summary || d.narrative;
  const summary = card.executive_summary || d.executive_summary || takeaway;
  const confidenceLabel = card.confidence_label || d.confidence_label || null;
  const metrics = (presentation?.kpis?.length
    ? presentation.kpis.map((k) => ({
        label: k.label,
        value: sanitizeDisplayText(k.formatted_value || k.value),
        unit: k.unit || '',
      }))
    : (card.supporting_metrics || d.metrics || []).map((m) => ({
        ...m,
        value: sanitizeDisplayText(m.value),
      })));
  const chartSpec = card.visualization || d.chart_spec || null;
  const secondary = card.secondary_interpretation || '';
  const recoveryMode = d.recovery_mode === true;
  const responseMode = d.response_mode || 'MIXED';
  const executionTrace = d.execution_trace || null;
  const executionPath = d.execution_path || null;
  const queryDebugPanel = d.query_debug_panel || null;
  const presentationTable = presentation?.table?.rows?.length
    ? {
        title: presentation.table.title || '',
        columns: (presentation.table.columns || []).map((c) => ({
          key: c.key,
          label: c.label,
          format: c.format || 'text',
        })),
        rows: presentation.table.rows,
        sortable: false,
        compact: true,
      }
    : null;
  const tableSpec = presentationTable || (d.table_spec?.rows?.length ? d.table_spec : null);
  const evidencePanel = d.evidence_panel || null;
  const analysisType = d.analysis_type || null;
  const correlationAnalysis = d.correlation_analysis || null;
  const semanticShadow = d.semantic_shadow || null;
  const semanticPlanner = d.semantic_planner || null;
  const gptServed = semanticPlanner?.served_path === 'gpt'
    || executionPath?.served_path === 'gpt';
  const isCorrelation = analysisType === 'CORRELATION_RESULT'
    || analysisType === 'CORRELATION'
    || correlationAnalysis != null;
  const showActions = !recoveryMode && confidenceLabel === 'High confidence' && (d.actions?.length > 0);

  const showPresentationTable = !isCorrelation && presentationTable?.rows?.length > 0;
  const showTable = !isCorrelation
    && !showPresentationTable
    && (responseMode === 'TABLE' || responseMode === 'MIXED')
    && tableSpec?.rows?.length > 0;
  const showChart = !isCorrelation
    && (responseMode === 'CHART' || responseMode === 'MIXED')
    && chartSpec?.type && Array.isArray(chartSpec.data) && chartSpec.data.length > 0;
  const showCorrelation = isCorrelation && (
    correlationAnalysis
    || (chartSpec?.type && String(chartSpec.type).toUpperCase() === 'CORRELATION')
  );
  const fallbackText = takeaway || summary || card.key_takeaway || d.key_takeaway
    || d.narrative || d.insight?.text || d.recovery_reason
    || (recoveryMode ? 'Analysis could not be fully verified, but partial results may be available below.' : '')
    || 'Analysis completed.';
  const synthesisApplied = d.answer_synthesis?.applied === true;
  const synthesisSummary = synthesisApplied ? d.answer_synthesis?.executive_summary : null;
  const executiveSummaryText = synthesisSummary
    || card.executive_summary
    || d.executive_summary
    || null;
  const primarySummary = executiveSummaryText || takeaway || fallbackText;
  const keyTakeawayText = card.key_takeaway || d.key_takeaway || null;
  const showKeyTakeaway = Boolean(keyTakeawayText)
    && keyTakeawayText !== primarySummary
    && keyTakeawayText !== executiveSummaryText;
  const activeTableSpec = showPresentationTable
    ? presentationTable
    : (showTable ? tableSpec : null);
  const hasContent = primarySummary || metrics.length > 0 || showChart || activeTableSpec
    || showCorrelation
    || executionTrace?.steps?.length > 0 || title;

  return (
    <div className="chat-msg chat-msg--ai">
      <span className="chat-msg-avatar chat-msg-avatar--decision">⚡</span>
      <div className="chat-msg-bubble chat-msg-bubble--decision">
        <div className="di-header-row">
          <p className="chat-msg-mode-label">
            {gptServed ? 'GPT Analysis' : 'Analysis'}
          </p>
          {gptServed && (
            <span className="gpt-served-badge">GPT planner</span>
          )}
          {confidenceLabel && (
            <span className={`di-confidence di-confidence--${confidenceLabel.toLowerCase().split(' ')[0]}`}>
              {confidenceLabel}
            </span>
          )}
        </div>

        {title && !isCorrelation && <h3 className="di-title">{title}</h3>}
        {isCorrelation && (
          <h3 className="di-title">
            {correlationAnalysis?.title || title || 'Correlation analysis'}
          </h3>
        )}

        {executionTrace && (
          <ExecutionTracePanel trace={executionTrace} animate />
        )}

        <GptPlannerPanel
          planner={semanticPlanner}
          runId={d.runId}
          sql={executionPath?.generated_sql}
        />

        {semanticShadow && <SemanticShadowPanel shadow={semanticShadow} runId={d.runId} />}

        {executionPath && (
          <details className="sql-debug-panel">
            <summary className="sql-debug-panel__summary">
              Warehouse execution ({executionPath.warehouse_row_count ?? 0} rows)
            </summary>
            <div className="sql-debug-panel__body">
              {executionPath.first_empty_stage ? (
                <p className="sql-debug-panel__label sql-debug-panel__label--warn">
                  First empty stage: {executionPath.first_empty_stage}
                </p>
              ) : null}
              <p className="sql-debug-panel__label">Question</p>
              <pre className="sql-debug-entry__sql">{executionPath.question || '(none)'}</pre>
              <p className="sql-debug-panel__label">
                Planner: {executionPath.served_path || semanticPlanner?.served_path || 'legacy'}
                {executionPath.planner_mode || semanticPlanner?.mode
                  ? ` (${executionPath.planner_mode || semanticPlanner?.mode})`
                  : ''}
              </p>
              <p className="sql-debug-panel__label">
                Resolved metric: {executionPath.resolved_metric || semanticPlanner?.metric || '(null)'}
              </p>
              <p className="sql-debug-panel__label">
                Resolved dimension: {executionPath.resolved_dimension || semanticPlanner?.dimension || '(null)'}
              </p>
              <p className="sql-debug-panel__label">
                Investigation plan: {executionPath.investigation_plan || '(null)'}
              </p>
              <p className="sql-debug-panel__label">
                Materialized query: {executionPath.materialized_query || 'NONE'}
              </p>
              <p className="sql-debug-panel__label">Generated SQL</p>
              <pre className="sql-debug-entry__sql">
                {executionPath.generated_sql || '(none)'}
              </pre>
              <p className="sql-debug-panel__label">
                BigQuery row count: {executionPath.warehouse_row_count ?? 0}
              </p>
              <p className="sql-debug-panel__label">First 20 rows</p>
              <pre className="sql-debug-entry__sql">
                {JSON.stringify(executionPath.sample_rows ?? [], null, 2)}
              </pre>
              {executionPath.bigquery_error ? (
                <>
                  <p className="sql-debug-panel__label">BigQuery error</p>
                  <pre className="sql-debug-entry__sql">{executionPath.bigquery_error}</pre>
                </>
              ) : null}
              <p className="sql-debug-panel__label">
                Rows discarded by validation:{' '}
                {executionPath.rows_discarded_by_validation ? 'Yes' : 'No'}
              </p>
              {executionPath.validation_discard_reason ? (
                <pre className="sql-debug-entry__sql">
                  {executionPath.validation_discard_reason}
                </pre>
              ) : null}
              {executionPath.materialization_failure_reason ? (
                <>
                  <p className="sql-debug-panel__label">Materialization failure</p>
                  <pre className="sql-debug-entry__sql">
                    {executionPath.materialization_failure_reason}
                  </pre>
                </>
              ) : null}
              <p className="sql-debug-panel__label">Materialized rows</p>
              <pre className="sql-debug-entry__sql">
                {JSON.stringify(executionPath.materialized_rows ?? [], null, 2)}
              </pre>
            </div>
          </details>
        )}

        {debug && queryDebugPanel && (
          <SqlDebugPanel panel={queryDebugPanel} />
        )}

        {primarySummary && (
          <p className={`di-executive-summary${recoveryMode ? ' di-executive-summary--recovery' : ''}`}>
            {primarySummary}
          </p>
        )}

        {showKeyTakeaway && (
          <p className={`di-takeaway${recoveryMode ? ' di-takeaway--recovery' : ''}`}>
            {keyTakeawayText}
          </p>
        )}

        {metrics.length > 0 && !isCorrelation && (
          <div className="di-metrics-row">
            {metrics.slice(0, 4).map((m, i) => (
              <div key={m.label || i} className="di-metric-chip">
                <span className="di-metric-label">{m.label}</span>
                <span className="di-metric-value">
                  {m.value}{m.unit ? ` ${m.unit}` : ''}
                </span>
                {m.context && <span className="di-metric-context">{m.context}</span>}
              </div>
            ))}
          </div>
        )}

        {showCorrelation && (
          <div className="di-chart-block di-chart-block--correlation">
            <CorrelationAnalysisCard
              analysis={correlationAnalysis}
              chartData={chartSpec?.data?.[0]}
            />
          </div>
        )}

        {showChart && (
          <div className="di-chart-block">
            {chartSpec.title && <p className="di-chart-title">{chartSpec.title}</p>}
            <ChartRenderer spec={chartSpec} />
          </div>
        )}

        {activeTableSpec && <TableRenderer spec={activeTableSpec} />}

        {presentation?.insights?.length > 0 && (
          <ul className="di-insights-list">
            {presentation.insights.map((insight, i) => (
              <li key={i} className="di-insight-item">{insight}</li>
            ))}
          </ul>
        )}

        {d.answer_synthesis?.follow_up_questions?.length > 0 && (
          <div className="di-follow-ups">
            <p className="di-section-label">FOLLOW-UP QUESTIONS</p>
            <ul>
              {d.answer_synthesis.follow_up_questions.map((q, i) => (
                <li key={i}>{q}</li>
              ))}
            </ul>
          </div>
        )}

        {secondary && secondary !== primarySummary && secondary !== keyTakeawayText && (
          <p className="di-secondary">{secondary}</p>
        )}

        {showActions && (
          <div className="di-section">
            <p className="di-section-label">NEXT STEPS</p>
            <ol className="di-list di-list--actions">
              {d.actions.slice(0, 3).map((a, i) => <li key={i}>{a}</li>)}
            </ol>
          </div>
        )}

        {evidencePanel && (evidencePanel.sample_size || evidencePanel.metric_used) && (
          <div className="di-evidence-panel">
            <p className="di-section-label">EVIDENCE</p>
            <div className="di-evidence-grid">
              {evidencePanel.metric_used && (
                <span><em>Metric</em> {evidencePanel.metric_used}</span>
              )}
              {evidencePanel.grouping_used && (
                <span><em>Grouping</em> {evidencePanel.grouping_used}</span>
              )}
              {evidencePanel.sample_size != null && (
                <span><em>Sample</em> {evidencePanel.sample_size}</span>
              )}
              {evidencePanel.aggregation_method && (
                <span><em>Aggregation</em> {evidencePanel.aggregation_method}</span>
              )}
            </div>
            {evidencePanel.confidence_basis && (
              <p className="di-evidence-basis">{evidencePanel.confidence_basis}</p>
            )}
          </div>
        )}

        {debug && <DecisionDebugPanel d={d} />}
      </div>
    </div>
  );
}

function DecisionDebugPanel({ d }) {
  const evidence = d.evidence_panel || null;
  const assumption = d.analytical_assumption || null;
  const clarifications = d.clarification_options || [];
  const findings = d.findings || [];

  return (
    <div className="di-debug-panel">
      <p className="di-section-label">DEBUG</p>
      {d.confidence != null && (
        <p className="di-debug-line">Raw confidence: {Math.round(d.confidence * 100)}%</p>
      )}
      {assumption && (
        <div className="di-assumption-grid">
          <span><em>Metric</em> {assumption.primary_metric_label || assumption.primary_metric}</span>
          <span><em>Grouping</em> {assumption.grouping}</span>
          <span><em>Aggregation</em> {assumption.aggregation}</span>
          <span><em>Intent</em> {assumption.intent}</span>
        </div>
      )}
      {evidence && (
        <div className="di-evidence-grid">
          <span><em>Sample</em> {evidence.sample_size}</span>
          <span><em>Aggregation</em> {evidence.aggregation_method}</span>
        </div>
      )}
      {clarifications.length > 0 && (
        <ul className="di-list di-list--clarifications">
          {clarifications.map((c, i) => <li key={i}>{c.label} — {c.description}</li>)}
        </ul>
      )}
      {findings.length > 0 && (
        <ul className="di-list di-list--findings">
          {findings.map((f, i) => <li key={i}>{f.label}: {f.summary}</li>)}
        </ul>
      )}
      {d.recovery_reason && <p className="di-debug-line">{d.recovery_reason}</p>}
    </div>
  );
}

/* ─── Main page ────────────────────────────────────────────── */
function UserDashboardPage() {
  const navigate = useNavigate();
  const session      = getSession() || {};
  const userId       = session.userId       || '';
  const tenantId     = session.tenantId     || '';
  const tenantSchema = session.tenantSchema || '';

  /* ── Agent state ──────────────────────────── */
  const [activeNav,   setActiveNav]   = useState('insights'); // insights | chat | data
  const [insightsView, setInsightsView] = useState('feed'); // feed | charts
  const [agentResult,  setAgentResult]  = useState(null);
  const [agentRunning, setAgentRunning] = useState(false);
  const [agentError,   setAgentError]   = useState('');
  const [dismissed,    setDismissed]    = useState(new Set());
  const [feedCards,    setFeedCards]    = useState([]);   // persisted cards loaded on mount
  const [feedLoading,  setFeedLoading]  = useState(true);
  const [feedFollowUp, setFeedFollowUp] = useState([]);  // suggested questions for persisted feed
  const [chartLoading, setChartLoading] = useState(false);
  const [chartError,   setChartError]   = useState('');
  const [chartItems,   setChartItems]   = useState([]);
  const [focusedChart, setFocusedChart] = useState(null);

  /* ── Chat state ───────────────────────────── */
  const [messages,       setMessages]       = useState([
    { id: 0, role: 'ai', text: "Hi! I'm your AI analyst. Ask me anything about your data, or click Refresh Insights to let me scan for patterns automatically." }
  ]);
  const [chatInput,      setChatInput]      = useState('');
  const [chatLoading,    setChatLoading]    = useState(false);
  const [decisionLoading, setDecisionLoading] = useState(false);
  const [plannerStatus, setPlannerStatus] = useState(null);
  const messagesEndRef = useRef(null);

  /* ── Tables (for chat context) ────────────── */
  const [availableTables, setAvailableTables] = useState([]);

  /* ── Load persisted cards + follow-up questions on mount ─────────── */
  useEffect(() => {
    fetchPlannerStatus()
      .then(setPlannerStatus)
      .catch(() => setPlannerStatus(null));
  }, []);

  useEffect(() => {
    if (!tenantSchema) { setFeedLoading(false); return; }

    getPersistedInsights(tenantSchema)
      .then(entities => {
        const cards = (entities || []).map(normalizePersistedCard);
        setFeedCards(cards);
        if (cards.length > 0) {
          getFollowUpQuestions(tenantSchema)
            .then(d => setFeedFollowUp(d.followUpQuestions || []))
            .catch(() => {});
        }
      })
      .catch(() => {})
      .finally(() => setFeedLoading(false));
  }, [tenantSchema]);

  useEffect(() => {
    if (!tenantSchema) return;
    setChartLoading(true);
    setChartError('');
    getAgentCharts(tenantSchema)
      .then((payload) => setChartItems(payload.charts || []))
      .catch((e) => setChartError(e.message || 'Failed to load charts'))
      .finally(() => setChartLoading(false));
  }, [tenantSchema]);

  useEffect(() => {
    if (!tenantSchema) return;
    fetchTablesForSchema(tenantSchema, tenantId)
      .then(p => setAvailableTables(p.tables || []))
      .catch(() => {});
  }, [tenantSchema, tenantId]);

  /* ── Scroll chat to bottom ────────────────── */
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const chartsToShow = focusedChart ? [focusedChart] : chartItems;

  /* ── Refresh insights ─────────────────────── */
  const handleRefresh = async () => {
    if (!tenantSchema || agentRunning) return;
    setAgentRunning(true);
    setAgentError('');
    try {
      const data = await runAgentDashboard(tenantSchema);
      if (data.errorMessage) {
        setAgentError(data.errorMessage);
      } else {
        setAgentResult(data);
        setDismissed(new Set());
        // Refresh the persisted feed so it includes the new cards
        getPersistedInsights(tenantSchema)
          .then(entities => setFeedCards((entities || []).map(normalizePersistedCard)))
          .catch(() => {});
        setChartLoading(true);
        setChartError('');
        getAgentCharts(tenantSchema)
          .then((payload) => setChartItems(payload.charts || []))
          .catch((e) => setChartError(e.message || 'Failed to load charts'))
          .finally(() => setChartLoading(false));
        getFollowUpQuestions(tenantSchema)
          .then(d => setFeedFollowUp(d.followUpQuestions || []))
          .catch(() => {});
        const count = data.insights?.length || 0;
        addAiMessage(`Analysis complete — ${count} insights found. Confidence: ${data.confidence}%. ${data.reasoning || ''}`);
      }
    } catch (err) {
      setAgentError(err.message || 'Agent analysis failed');
    } finally {
      setAgentRunning(false);
    }
  };

  /* ── Chat ─────────────────────────────────── */
  const addAiMessage = (text, extras = {}) => {
    setMessages(prev => [...prev, { id: Date.now(), role: 'ai', text, ...extras }]);
  };

  const handleSendChat = async (overrideText) => {
    const text = (overrideText || chatInput).trim();
    if (!text || chatLoading) return;
    setChatInput('');

    const userMsg = { id: Date.now(), role: 'user', text };
    const loadingMsg = { id: Date.now() + 1, role: 'ai', loading: true };
    setMessages(prev => [...prev, userMsg, loadingMsg]);
    setChatLoading(true);

    try {
      const history = messages
        .filter((m) => (m.text || '').trim())
        .slice(-8)
        .map((m) => ({ role: m.role === 'user' ? 'user' : 'assistant', text: m.text }));
      const payload = await askQuestion(tenantSchema, text, overrideText != null, history);

      // Clarification needed — agent asks a follow-up question
      if (payload.needsClarification) {
        setMessages(prev => prev.filter(m => !m.loading).concat({
          id: Date.now(),
          role: 'ai',
          text: payload.clarifyingQuestion,
          isClarification: true,
        }));
        return;
      }

      const type = payload.type || 'sql';

      if (type === 'reasoning') {
        // Pure analytical answer — no SQL, no table
        setMessages(prev => prev.filter(m => !m.loading).concat({
          id: Date.now(),
          role: 'ai',
          type: 'reasoning',
          text: payload.answer,
          followUpSuggestions: payload.followUpSuggestions || [],
        }));
      } else if (type === 'mixed') {
        // SQL result + plain-English explanation
        setMessages(prev => prev.filter(m => !m.loading).concat({
          id: Date.now(),
          role: 'ai',
          type: 'mixed',
          text: payload.answer || `Found ${payload.rowCount} row${payload.rowCount === 1 ? '' : 's'}.`,
          sql: payload.generatedSql,
          rows: payload.rows || [],
          followUpSuggestions: payload.followUpSuggestions || [],
        }));
      } else {
        // Plain SQL result
        setMessages(prev => prev.filter(m => !m.loading).concat({
          id: Date.now(),
          role: 'ai',
          type: 'sql',
          text: `Found ${payload.rowCount} row${payload.rowCount === 1 ? '' : 's'}.`,
          sql: payload.generatedSql,
          rows: payload.rows || [],
          followUpSuggestions: payload.followUpSuggestions || [],
        }));
      }
    } catch (err) {
      setMessages(prev => prev.filter(m => !m.loading).concat({
        id: Date.now(),
        role: 'ai',
        error: err.message || 'Query failed',
      }));
    } finally {
      setChatLoading(false);
    }
  };

  const handleDeepAnalysis = async (overrideText) => {
    const text = (overrideText || chatInput).trim();
    if (!text || decisionLoading || chatLoading) return;
    setChatInput('');

    const userMsg   = { id: Date.now(),     role: 'user', text };
    const loadingMsg = {
      id: Date.now() + 1,
      role: 'ai',
      type: 'decision-loading',
      question: text,
      loading: true,
    };
    setMessages(prev => [...prev, userMsg, loadingMsg]);
    setDecisionLoading(true);

    try {
      const result = await runDecisionAnalysis(tenantSchema, text);
      setMessages(prev => prev.filter(m => !m.loading).concat({
        id:       Date.now(),
        role:     'ai',
        type:     'decision',
        decision: result,
      }));
    } catch (err) {
      setMessages(prev => prev.filter(m => m.loading).concat({
        id:       Date.now(),
        role:     'ai',
        type:     'decision',
        decision: {
          title: 'Analysis unavailable',
          key_takeaway: err.message || 'Deep analysis failed. Check that the backend is running.',
          recovery_mode: true,
        },
      }));
    } finally {
      setDecisionLoading(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleDeepAnalysis();
    }
  };

  const handleViewChart = (card) => {
    const entry = resolveInsightChart(card, chartItems);
    if (!entry) return;
    setFocusedChart(entry);
    setActiveNav('insights');
    setInsightsView('charts');
  };

  const removeFromFeed = (item) => {
    const key = feedKey(item);
    setDismissed(prev => new Set([...prev, key]));
    setFeedCards(prev => prev.filter(c => feedKey(c) !== key));
    if (agentResult) {
      setAgentResult(prev => ({
        ...prev,
        insights: (prev.insights || []).filter(c => feedKey(c) !== key),
        anomalies: (prev.anomalies || []).filter(a => feedKey(a) !== key),
      }));
    }
  };

  /** User understood — hide from feed; persist when card has a database id */
  const handleMarkAsRead = async (item) => {
    removeFromFeed(item);
    if (item.id) {
      updateInsightStatus(tenantSchema, item.id, 'COMPLETED').catch(() => {});
    }
  };

  /** Not relevant — hide from feed; optional reason feeds decision memory */
  const handleDismiss = async (item) => {
    const reason = window.prompt(
      'Why dismiss? (optional — helps improve future insights)',
      ''
    );
    removeFromFeed(item);
    if (item.id) {
      updateInsightStatus(
        tenantSchema,
        item.id,
        'DECLINED',
        reason && reason.trim() ? reason.trim() : null
      ).catch(() => {});
    }
  };

  /* ── Jump to chat with prefilled question ─── */
  const handleAsk = (question) => {
    setActiveNav('chat');
    handleDeepAnalysis(question);
  };

  /* ── Sign out ─────────────────────────────── */
  const handleSignOut = () => {
    clearSession();
    navigate('/signin');
  };

  /* ── Visible cards ────────────────────────── */
  // After a fresh run use the live result; otherwise fall back to persisted feed
  const isVisible = (item) => !dismissed.has(feedKey(item));
  const liveInsights     = (agentResult?.insights  || []).filter(isVisible);
  const liveAnomalies    = (agentResult?.anomalies || []).filter(isVisible);
  const persistedVisible = agentResult ? [] : feedCards.filter(isVisible);
  const visibleInsights  = sortByImpact(agentResult ? liveInsights : persistedVisible);
  const dailyBrief       = agentResult?.dailyBrief;
  const visibleAnomalies = agentResult ? liveAnomalies : [];
  const totalCards       = visibleInsights.length + visibleAnomalies.length;
  const usingPersistedFeed = !agentResult && persistedVisible.length > 0;

  /* ══════════════════════════════════════════ */
  return (
    <div className="inbox-shell app-shell">
      <aside className="app-sidebar">
        <div className="app-sidebar-top">
          <div className="app-brand">
            <span className="app-brand-logo">K</span>
            <div>
              <p className="app-brand-title">KONTEXA</p>
              <p className="app-brand-sub">{userId || 'User'}</p>
            </div>
          </div>
          <nav className="app-nav">
            <button
              type="button"
              className={`app-nav-btn ${activeNav === 'insights' ? 'active' : ''}`}
              onClick={() => setActiveNav('insights')}
            >
              <span>Insights</span>
              {totalCards > 0 ? <span className="app-nav-count">{totalCards}</span> : null}
            </button>
            <button
              type="button"
              className={`app-nav-btn ${activeNav === 'chat' ? 'active' : ''}`}
              onClick={() => setActiveNav('chat')}
            >
              <span>Chat</span>
            </button>
            <button
              type="button"
              className={`app-nav-btn ${activeNav === 'data' ? 'active' : ''}`}
              onClick={() => setActiveNav('data')}
            >
              <span>Data</span>
              <span className="app-nav-count">{availableTables.length}</span>
            </button>
          </nav>
        </div>

        <button className="app-signout-btn" type="button" onClick={handleSignOut}>
          Sign out
        </button>
      </aside>

      <main className="app-main">
        <header className="app-main-header">
          <div>
            <h1>
              {activeNav === 'insights'
                ? 'Insights'
                : activeNav === 'chat'
                  ? 'AI Chat'
                  : 'Data Explorer'}
            </h1>
            <p>
              {activeNav === 'insights'
                ? 'Insights are generated highlights from your data.'
                : activeNav === 'chat'
                  ? 'Ask questions and explore answers with your connected warehouse.'
                  : `Connected workspace ${tenantId || ''} · ${availableTables.length} discovered tables.`}
            </p>
          </div>
          <div className="app-header-actions">
            {activeNav === 'insights' ? (
              <div className="inbox-tabs" role="tablist" aria-label="Insight content tabs">
                <button
                  type="button"
                  role="tab"
                  aria-selected={insightsView === 'feed'}
                  className={`inbox-tab ${insightsView === 'feed' ? 'active' : ''}`}
                  onClick={() => setInsightsView('feed')}
                >
                  Feed
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={insightsView === 'charts'}
                  className={`inbox-tab ${insightsView === 'charts' ? 'active' : ''}`}
                  onClick={() => {
                    setFocusedChart(null);
                    setInsightsView('charts');
                  }}
                >
                  Charts
                  {chartItems?.length > 0 ? <span className="inbox-count-badge inbox-count-badge--tab">{chartItems.length}</span> : null}
                </button>
              </div>
            ) : null}

            <button
              className={`inbox-refresh-btn ${agentRunning ? 'loading' : ''}`}
              type="button"
              onClick={handleRefresh}
              disabled={agentRunning}
            >
              {agentRunning ? <><span className="inbox-spinner" /> Analysing...</> : '⟳ Refresh Insights'}
            </button>
          </div>
        </header>

        {(plannerStatus?.gpt_active || plannerStatus?.shadow_active) && (
          <div className={`planner-shadow-banner ${plannerStatus?.gpt_active ? 'planner-shadow-banner--gpt' : ''}`} role="status">
            <span className="planner-shadow-banner__dot" />
            <strong>{plannerStatus.gpt_active ? 'GPT planner active' : 'Shadow mode'}</strong>
            <span>
              {plannerStatus.gpt_active
                ? 'Questions are answered using the GPT + Catalogue semantic planner.'
                : 'Legacy answers are served. Each analysis includes a Legacy vs GPT comparison panel.'}
            </span>
            <span className="planner-shadow-banner__meta">
              mode={plannerStatus.mode}
            </span>
          </div>
        )}

        <section className="app-main-body">
          {activeNav === 'insights' ? (
            insightsView === 'feed' ? (
              <div className="inbox-feed">
                {agentRunning ? (
                  <div className="feed-loading">
                    <div className="feed-loading-dots"><span/><span/><span/></div>
                    <p>Agent is scanning your data for insights, trends, and anomalies...</p>
                  </div>
                ) : null}

                {agentError && !agentRunning ? (
                  <div className="feed-error">
                    <p>⚠️ {agentError}</p>
                  </div>
                ) : null}

                {dailyBrief && !agentRunning ? (
                  <div className="feed-daily-brief">
                    <p className="feed-daily-brief-label">Leadership brief</p>
                    <p className="feed-daily-brief-text">{dailyBrief}</p>
                  </div>
                ) : null}

                {usingPersistedFeed ? (
                  <div className="feed-persisted-banner">
                    Showing saved insights · Click <strong>Refresh Insights</strong> to run a new analysis
                  </div>
                ) : null}

                {!agentRunning && !agentError && totalCards === 0 && !feedLoading ? (
                  <div className="feed-empty">
                    <div className="feed-empty-icon">🤖</div>
                    <h2>No insights yet</h2>
                    <p>Click <strong>Refresh Insights</strong> to let the agent scan your data for trends, anomalies, and opportunities.</p>
                    <button className="feed-empty-btn" type="button" onClick={handleRefresh}>
                      Run Analysis
                    </button>
                  </div>
                ) : null}

                {visibleInsights.map((card, i) => (
                  <InsightCard
                    key={card.id || i}
                    card={card}
                    showChart={hasInsightChart(card, chartItems)}
                    onViewChart={handleViewChart}
                    onMarkAsRead={handleMarkAsRead}
                    onDismiss={handleDismiss}
                  />
                ))}

                {visibleAnomalies.map((a) => (
                  <AnomalyCard
                    key={feedKey(a)}
                    anomaly={a}
                    onMarkAsRead={handleMarkAsRead}
                    onDismiss={handleDismiss}
                  />
                ))}

                {(() => {
                  const questions = agentResult?.followUpQuestions?.length > 0
                    ? agentResult.followUpQuestions
                    : feedFollowUp;
                  return questions.length > 0 && totalCards > 0 ? (
                    <div className="feed-suggestions">
                      <p className="feed-suggestions-label">Suggested questions</p>
                      <div className="feed-suggestion-chips">
                        {questions.map((q, i) => (
                          <button key={i} className="feed-suggestion-chip" type="button" onClick={() => handleAsk(q)}>
                            {q}
                          </button>
                        ))}
                      </div>
                    </div>
                  ) : null;
                })()}
              </div>
            ) : (
              <div className="charts-page">
                <div className="charts-page-head">
                  <h2>Charts</h2>
                  <p>{focusedChart ? 'Chart for the selected insight.' : 'Charts generated from your latest saved insights.'}</p>
                </div>
                <div className="charts-page-content">
                  <ChartsPanel
                    loading={chartLoading}
                    error={chartError}
                    charts={chartsToShow}
                    focusedOnly={Boolean(focusedChart)}
                    onShowAll={() => setFocusedChart(null)}
                  />
                </div>
              </div>
            )
          ) : null}

          {activeNav === 'chat' ? (
            <div className="chat-page">
              <div className="chat-page-wrap">
                <div className="chat-messages-wrap">
                  {messages.map((msg) =>
                    msg.type === 'decision'
                      ? <DecisionInsightMessage key={msg.id} msg={msg} />
                      : msg.type === 'decision-loading'
                        ? <DecisionAnalysisLoading key={msg.id} question={msg.question} />
                        : <ChatMessage key={msg.id} msg={msg} onAsk={handleAsk} />
                  )}
                  <div ref={messagesEndRef} />
                </div>
                <div className="chat-input-area">
                  <textarea
                    className="chat-textarea"
                    value={chatInput}
                    onChange={(e) => setChatInput(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Ask anything about your data..."
                    rows={2}
                    disabled={decisionLoading}
                  />
                  <div className="chat-input-actions">
                    <button
                      className={`chat-deep-btn chat-deep-btn--primary ${decisionLoading ? 'loading' : ''}`}
                      type="button"
                      onClick={handleDeepAnalysis}
                      disabled={decisionLoading || !chatInput.trim()}
                      title="Deep Analysis — GPT + Catalogue semantic planner"
                    >
                      {decisionLoading ? <span className="chat-deep-spinner" /> : '⚡'}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          ) : null}

          {activeNav === 'data' ? (
            <div className="data-page">
              <div className="data-card">
                <h3>Connected Source</h3>
                <p className="data-card-sub">{tenantSchema || 'Unknown schema'}</p>
                <div className="data-meta-grid">
                  <div>
                    <span>Tenant</span>
                    <strong>{tenantId || 'N/A'}</strong>
                  </div>
                  <div>
                    <span>User</span>
                    <strong>{userId || 'N/A'}</strong>
                  </div>
                  <div>
                    <span>Tables discovered</span>
                    <strong>{availableTables.length}</strong>
                  </div>
                </div>
              </div>

              <div className="data-card">
                <h3>Tables</h3>
                {availableTables.length === 0 ? (
                  <p className="data-card-sub">No tables loaded yet for this workspace.</p>
                ) : (
                  <ul className="data-table-list">
                    {availableTables.map((table) => (
                      <li key={table}>{table}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          ) : null}
        </section>
      </main>
    </div>
  );
}

export default UserDashboardPage;
