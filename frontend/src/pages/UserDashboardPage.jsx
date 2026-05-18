import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { askQuestion, fetchTablesForSchema, runAgentDashboard } from '../api/queryApi';
import './UserDashboardPage.css';

/* ─── Small icon helper ─────────────────────────────────── */
const Icon = ({ name }) => {
  const icons = {
    home:       <svg viewBox="0 0 20 20" fill="currentColor"><path d="M10.707 2.293a1 1 0 00-1.414 0l-7 7A1 1 0 003 11h1v6a1 1 0 001 1h4v-4h2v4h4a1 1 0 001-1v-6h1a1 1 0 00.707-1.707l-7-7z"/></svg>,
    insights:   <svg viewBox="0 0 20 20" fill="currentColor"><path d="M2 11a1 1 0 011-1h2a1 1 0 011 1v5a1 1 0 01-1 1H3a1 1 0 01-1-1v-5zm6-4a1 1 0 011-1h2a1 1 0 011 1v9a1 1 0 01-1 1H9a1 1 0 01-1-1V7zm6-3a1 1 0 011-1h2a1 1 0 011 1v12a1 1 0 01-1 1h-2a1 1 0 01-1-1V4z"/></svg>,
    ask:        <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M18 10c0 3.866-3.582 7-8 7a8.841 8.841 0 01-4.083-.98L2 17l1.338-3.123C2.493 12.767 2 11.434 2 10c0-3.866 3.582-7 8-7s8 3.134 8 7zM7 9H5v2h2V9zm8 0h-2v2h2V9zM9 9h2v2H9V9z" clipRule="evenodd"/></svg>,
    dashboards: <svg viewBox="0 0 20 20" fill="currentColor"><path d="M5 3a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2V5a2 2 0 00-2-2H5zm0 8a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2v-2a2 2 0 00-2-2H5zm6-6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V5zm0 8a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"/></svg>,
    reports:    <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4zm2 6a1 1 0 011-1h6a1 1 0 110 2H7a1 1 0 01-1-1zm1 3a1 1 0 100 2h6a1 1 0 100-2H7z" clipRule="evenodd"/></svg>,
    datasrc:    <svg viewBox="0 0 20 20" fill="currentColor"><path d="M3 12v3c0 1.657 3.134 3 7 3s7-1.343 7-3v-3c0 1.657-3.134 3-7 3s-7-1.343-7-3z"/><path d="M3 7v3c0 1.657 3.134 3 7 3s7-1.343 7-3V7c0 1.657-3.134 3-7 3S3 8.657 3 7z"/><path d="M17 5c0 1.657-3.134 3-7 3S3 6.657 3 5s3.134-3 7-3 7 1.343 7 3z"/></svg>,
    catalogue:  <svg viewBox="0 0 20 20" fill="currentColor"><path d="M9 4.804A7.968 7.968 0 005.5 4c-1.255 0-2.443.29-3.5.804v10A7.969 7.969 0 015.5 14c1.669 0 3.218.51 4.5 1.385A7.962 7.962 0 0114.5 14c1.255 0 2.443.29 3.5.804v-10A7.968 7.968 0 0014.5 4c-1.255 0-2.443.29-3.5.804V12a1 1 0 11-2 0V4.804z"/></svg>,
    saved:      <svg viewBox="0 0 20 20" fill="currentColor"><path d="M5 4a2 2 0 012-2h6a2 2 0 012 2v14l-5-2.5L5 18V4z"/></svg>,
    history:    <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clipRule="evenodd"/></svg>,
    users:      <svg viewBox="0 0 20 20" fill="currentColor"><path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zm8 0a3 3 0 11-6 0 3 3 0 016 0zM8 16a4 4 0 118 0H0a4 4 0 118 0z"/></svg>,
    settings:   <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd"/></svg>,
  };
  return <span className="ud-nav-icon">{icons[name] || null}</span>;
};

function UserDashboardPage() {
  const navigate = useNavigate();
  const session = JSON.parse(window.sessionStorage.getItem('kontexaUserSession') || '{}');
  const userId       = session.userId       || '';
  const tenantId     = session.tenantId     || '';
  const tenantSchema = session.tenantSchema || '';
  const position     = session.position     || '';

  /* ── View routing ───────────────────────────── */
  const [activeView, setActiveView] = useState('home');

  /* ── Agent state ────────────────────────────── */
  const [agentResult, setAgentResult]       = useState(null);
  const [agentRunning, setAgentRunning]     = useState(false);
  const [agentError, setAgentError]         = useState('');

  /* ── Ask AI state ───────────────────────────── */
  const [question, setQuestion]         = useState('');
  const [activeMode, setActiveMode]     = useState('table');
  const [availableTables, setAvailableTables] = useState([]);
  const [selectedTable, setSelectedTable]     = useState('');
  const [result, setResult]             = useState(null);
  const [columns, setColumns]           = useState([]);
  const [errorMessage, setErrorMessage] = useState('');
  const [isRunning, setIsRunning]       = useState(false);

  /* ── Load tables on mount ───────────────────── */
  useEffect(() => {
    if (!tenantSchema) return;
    async function loadTables() {
      try {
        const payload = await fetchTablesForSchema(tenantSchema, tenantId);
        const tables = payload.tables || [];
        setAvailableTables(tables);
        setSelectedTable(tables[0] || '');
      } catch (err) {
        setErrorMessage(err.message || 'Failed to load tables');
      }
    }
    loadTables();
  }, [tenantSchema, tenantId]);

  /* ── Run NLP query ──────────────────────────── */
  const handleRunQuestion = async () => {
    const q = question.trim();
    if (!q || !tenantSchema) return;
    try {
      setIsRunning(true);
      setErrorMessage('');
      const payload = await askQuestion(tenantSchema, q);
      setResult(payload);
      const allKeys = new Set();
      (payload.rows || []).forEach((row) => Object.keys(row || {}).forEach((k) => allKeys.add(k)));
      setColumns(Array.from(allKeys));
    } catch (err) {
      setErrorMessage(err.message || 'Failed to run query');
    } finally {
      setIsRunning(false);
    }
  };

  const chartRows = useMemo(() => (result?.rows || []).slice(0, 8), [result]);

  /* ── Sign out ───────────────────────────────── */
  const handleSignOut = () => {
    window.sessionStorage.removeItem('kontexaUserSession');
    navigate('/signin');
  };

  /* ── Helpers ────────────────────────────────── */
  const getGreeting = () => {
    const h = new Date().getHours();
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  };

  const getDateRange = () => {
    const now = new Date();
    const prev = new Date(now - 7 * 24 * 60 * 60 * 1000);
    const fmt = (d) => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    return `${fmt(prev)} – ${fmt(now)}, ${now.getFullYear()}`;
  };

  /* ── Run agent analysis ─────────────────────── */
  const handleRefreshInsights = async () => {
    if (!tenantSchema) return;
    try {
      setAgentRunning(true);
      setAgentError('');
      const data = await runAgentDashboard(tenantSchema);
      if (data.errorMessage) {
        setAgentError(data.errorMessage);
      } else {
        setAgentResult(data);
      }
    } catch (err) {
      setAgentError(err.message || 'Agent analysis failed');
    } finally {
      setAgentRunning(false);
    }
  };

  /* ── Ask AI: jump from home bar ─────────────── */
  const jumpToAsk = (q = '') => {
    if (q) setQuestion(q);
    setActiveView('ask');
  };

  /* ════════════════════════════════════════════ */
  return (
    <div className="ud-shell">

      {/* ── LEFT SIDEBAR ──────────────────────── */}
      <aside className="ud-sidebar">
        <div className="ud-sidebar-logo">
          <span className="ud-logo-mark">K</span>
          <span className="ud-logo-text">KONTEXA</span>
        </div>

        <nav className="ud-nav">
          <div className="ud-nav-group">
            <span className="ud-nav-label">MAIN</span>
            <NavItem icon="home"       label="Home"       active={activeView === 'home'}     onClick={() => setActiveView('home')} />
            <NavItem icon="insights"   label="Insights"   active={activeView === 'insights'} onClick={() => setActiveView('insights')} disabled />
            <NavItem icon="ask"        label="Ask AI"     active={activeView === 'ask'}      onClick={() => setActiveView('ask')} />
            <NavItem icon="dashboards" label="Dashboards" disabled />
            <NavItem icon="reports"    label="Reports"    disabled />
          </div>

          <div className="ud-nav-group">
            <span className="ud-nav-label">DATA</span>
            <NavItem icon="datasrc"   label="Data Sources" disabled />
            <NavItem icon="catalogue" label="Catalogues"   disabled />
          </div>

          <div className="ud-nav-group">
            <span className="ud-nav-label">WORKSPACE</span>
            <NavItem icon="saved"   label="Saved Analyses" disabled />
            <NavItem icon="history" label="Query History"  disabled />
          </div>

          <div className="ud-nav-group">
            <span className="ud-nav-label">ADMIN</span>
            <NavItem icon="users"    label="Users &amp; Roles" disabled />
            <NavItem icon="settings" label="Settings"          disabled />
          </div>
        </nav>

        <div className="ud-sidebar-footer">
          <div className="ud-tenant-badge">
            <div className="ud-tenant-avatar">
              {(tenantId || 'T')[0].toUpperCase()}
            </div>
            <div className="ud-tenant-info">
              <p className="ud-tenant-name">{tenantId || 'Unknown'}</p>
              <p className="ud-tenant-plan">Enterprise Plan</p>
            </div>
          </div>
          <button className="ud-signout-btn" onClick={handleSignOut} type="button">
            Sign Out
          </button>
        </div>
      </aside>

      {/* ── MAIN CONTENT ──────────────────────── */}
      <main className="ud-main">

        {/* HOME VIEW */}
        {activeView === 'home' && (
          <div className="ud-home-view">

            {/* Header */}
            <div className="ud-home-header">
              <div>
                <h1 className="ud-greeting">{getGreeting()}, {userId || 'User'} 👋</h1>
                <p className="ud-subtext">Here's what's happening with your data today.</p>
              </div>
              <div className="ud-header-actions">
                <span className="ud-date-pill">📅 {getDateRange()}</span>
                <button
                  className="ud-refresh-btn"
                  type="button"
                  onClick={handleRefreshInsights}
                  disabled={agentRunning}
                >
                  {agentRunning ? 'Analysing...' : 'Refresh Insights'}
                </button>
              </div>
            </div>

            {/* KPI Cards */}
            {agentRunning && (
              <div className="ud-agent-loading">
                <span className="ud-loading-pulse" />
                Agent is analysing your data...
              </div>
            )}
            {agentError && <p className="ud-agent-error">{agentError}</p>}

            <div className="ud-kpi-row">
              {agentResult?.kpiCards?.length > 0
                ? agentResult.kpiCards.map((card) => (
                    <div className="ud-kpi-card" key={card.metric}>
                      <p className="ud-kpi-label">{card.metric}</p>
                      <p className="ud-kpi-value">{card.displayValue}</p>
                      <p className={`ud-kpi-change ${card.direction === 'UP' ? 'up' : card.direction === 'DOWN' ? 'down' : ''}`}>
                        {card.direction === 'UP' ? '↑' : card.direction === 'DOWN' ? '↓' : '→'}
                        {' '}{Math.abs(card.changePercent).toFixed(1)}% vs prev period
                      </p>
                    </div>
                  ))
                : ['Total Revenue', 'Active Users', 'Conversion Rate', 'Churn Rate'].map((label) => (
                    <div className="ud-kpi-card" key={label}>
                      <p className="ud-kpi-label">{label}</p>
                      <p className="ud-kpi-value ud-kpi-empty">—</p>
                      <p className="ud-kpi-hint">Click Refresh Insights</p>
                    </div>
                  ))
              }
            </div>

            {/* AI Insights */}
            <div className="ud-section">
              <div className="ud-section-header">
                <span className="ud-section-title">🤖 AI Insights</span>
                <button className="ud-view-all" type="button">View all insights →</button>
              </div>
              <div className="ud-insights-grid">
                {agentResult?.insights?.length > 0
                  ? agentResult.insights.map((ins, i) => (
                      <div className="ud-insight-card" key={i}>
                        <span className={`ud-insight-badge ${ins.impactLevel?.toLowerCase()}`}>
                          {ins.impactLevel}
                        </span>
                        <p className="ud-insight-title">{ins.title}</p>
                        <p className="ud-insight-desc">{ins.description}</p>
                        <button className="ud-investigate-btn" type="button"
                          onClick={() => jumpToAsk(ins.title)}>
                          Investigate
                        </button>
                      </div>
                    ))
                  : (
                    <div className="ud-insight-placeholder">
                      <p>Click <strong>Refresh Insights</strong> to generate AI-powered insights, anomaly detection, and trend analysis from your connected data source.</p>
                    </div>
                  )
                }
              </div>
            </div>

            {/* AI Investigations + Anomalies row */}
            <div className="ud-two-col">
              <div className="ud-section">
                <div className="ud-section-header">
                  <span className="ud-section-title">AI Investigations In Progress</span>
                </div>
                <div className="ud-investigations-list">
                  {agentResult?.investigations?.length > 0
                    ? agentResult.investigations.map((inv, i) => (
                        <div className="ud-investigation-item" key={i}>
                          <div>
                            <p className="ud-item-title">{inv.title}</p>
                            <p className="ud-item-sub">{inv.description}</p>
                          </div>
                          <span className="ud-status-badge">Suggested</span>
                        </div>
                      ))
                    : <p className="ud-list-empty">No active investigations. Refresh Insights to start.</p>
                  }
                </div>
              </div>
              <div className="ud-section">
                <div className="ud-section-header">
                  <span className="ud-section-title">Recent Anomalies</span>
                  <button className="ud-view-all" type="button">View all →</button>
                </div>
                <div className="ud-anomalies-list">
                  {agentResult?.anomalies?.length > 0
                    ? agentResult.anomalies.map((a, i) => (
                        <div className="ud-anomaly-item" key={i}>
                          <div>
                            <p className="ud-item-title">{a.metric}</p>
                            <p className="ud-item-sub">{a.description}</p>
                          </div>
                          <span className={`ud-anomaly-change ${a.direction === 'UP' ? 'up' : 'down'}`}>
                            {a.direction === 'UP' ? '+' : ''}{a.changePercent?.toFixed(1)}%
                          </span>
                        </div>
                      ))
                    : <p className="ud-list-empty">No anomalies detected yet.</p>
                  }
                </div>
              </div>
            </div>

            {/* Ask anything bar */}
            <div className="ud-ask-bar" onClick={() => jumpToAsk()}>
              <svg className="ud-ask-bar-icon" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M8 4a4 4 0 100 8 4 4 0 000-8zM2 8a6 6 0 1110.89 3.476l4.817 4.817a1 1 0 01-1.414 1.414l-4.816-4.816A6 6 0 012 8z" clipRule="evenodd"/>
              </svg>
              <span className="ud-ask-bar-placeholder">Ask anything about your data... (e.g., Why did revenue drop in Q3?)</span>
              <span className="ud-ask-bar-shortcut">⌘K</span>
            </div>

            {/* Quick suggestion chips */}
            <div className="ud-suggestion-chips">
              {['Why did churn increase last month?', 'Compare revenue by region', 'Top products by growth'].map((s) => (
                <button key={s} className="ud-suggestion-chip" type="button" onClick={() => jumpToAsk(s)}>
                  {s}
                </button>
              ))}
            </div>

            {/* Activity Feed */}
            <div className="ud-section">
              <div className="ud-section-header">
                <span className="ud-section-title">AI Activity Feed</span>
                <span className="ud-live-badge">● Live</span>
              </div>
              <div className="ud-activity-feed">
                <p className="ud-list-empty">Activity will appear here after running your first analysis.</p>
              </div>
            </div>

          </div>
        )}

        {/* ASK AI VIEW */}
        {activeView === 'ask' && (
          <div className="ud-ask-view">

            <div className="ud-ask-view-header">
              <div>
                <h1>Ask AI</h1>
                <p>Ask questions about your data and get instant SQL-powered answers.</p>
              </div>
              <div className="mode-tabs">
                {[
                  { key: 'table', label: 'Table' },
                  { key: 'chart', label: 'Chart' },
                  { key: 'graph', label: 'Graph' },
                ].map(({ key, label }) => (
                  <button
                    key={key}
                    className={activeMode === key ? 'active' : ''}
                    onClick={() => setActiveMode(key)}
                    type="button"
                    disabled={!result}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            <div className="ud-ask-layout">

              {/* Tables panel */}
              <aside className="ud-tables-panel">
                <p className="ud-tables-label">TABLES</p>
                <p className="ud-tables-schema">{tenantSchema || '—'}</p>
                <ul className="table-list">
                  {availableTables.map((t) => (
                    <li key={t}>
                      <button
                        className={selectedTable === t ? 'active' : ''}
                        type="button"
                        onClick={() => setSelectedTable(t)}
                      >
                        {t}
                      </button>
                    </li>
                  ))}
                </ul>
              </aside>

              {/* Workspace */}
              <div className="ud-ask-workspace">
                <div className="question-card">
                  <p className="table-context">
                    Context table: <strong>{selectedTable || 'None'}</strong>
                  </p>
                  <textarea
                    rows={4}
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    placeholder="Ask your data question..."
                    onKeyDown={(e) => { if (e.key === 'Enter' && e.metaKey) handleRunQuestion(); }}
                  />
                  <button type="button" onClick={handleRunQuestion} disabled={isRunning || !question.trim()}>
                    {isRunning ? 'Running...' : 'Run Question'}
                  </button>
                </div>

                {errorMessage && <p className="workspace-error">{errorMessage}</p>}

                {result && (
                  <section className="result-panel">
                    <div className="result-meta">
                      <p>{result.rowCount} rows returned</p>
                      <small>SQL for {tenantSchema}</small>
                    </div>
                    <pre>{result.generatedSql}</pre>

                    {activeMode === 'table' && (
                      result.rowCount > 0 ? (
                        <div className="result-table-outer">
                          <table className="result-table">
                            <thead>
                              <tr>{columns.map((c) => <th key={c}>{c}</th>)}</tr>
                            </thead>
                            <tbody>
                              {result.rows.map((row, i) => (
                                <tr key={i}>
                                  {columns.map((c) => <td key={c}>{row[c] ?? '—'}</td>)}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      ) : (
                        <p className="empty-state">No rows returned for this query.</p>
                      )
                    )}

                    {activeMode === 'chart' && (
                      <div className="bar-chart">
                        {chartRows.map((row, i) => {
                          const val = Number(row[columns[1]]) || 0;
                          const w = Math.max(8, Math.min(100, val || 12));
                          return (
                            <div className="bar-item" key={i}>
                              <span>{String(row[columns[0]] ?? `Row ${i + 1}`)}</span>
                              <div className="bar-track"><div className="bar-fill" style={{ width: `${w}%` }} /></div>
                              <strong>{val || '—'}</strong>
                            </div>
                          );
                        })}
                      </div>
                    )}

                    {activeMode === 'graph' && (
                      <svg className="line-graph" viewBox="0 0 780 240" preserveAspectRatio="none">
                        <polyline
                          fill="none" stroke="#1de9d4" strokeWidth="3"
                          points={chartRows.map((row, i) => {
                            const y = 220 - Math.max(0, Math.min(200, Number(row[columns[1]]) || 0));
                            const x = chartRows.length <= 1 ? 0 : (i / (chartRows.length - 1)) * 760 + 10;
                            return `${x},${y}`;
                          }).join(' ')}
                        />
                      </svg>
                    )}
                  </section>
                )}
              </div>
            </div>
          </div>
        )}

        {/* INSIGHTS VIEW — placeholder */}
        {activeView === 'insights' && (
          <div className="ud-placeholder-view">
            <h1>Insights</h1>
            <p>Deep-dive insights are coming in the next phase.</p>
          </div>
        )}

      </main>

      {/* ── RIGHT CONTEXT PANEL ───────────────── */}
      <aside className="ud-right-panel">

        <div className="ud-panel-section">
          <p className="ud-panel-title">Context</p>
          <div className="ud-panel-row">
            <span className="ud-panel-icon">
              <svg viewBox="0 0 20 20" fill="currentColor"><path d="M3 12v3c0 1.657 3.134 3 7 3s7-1.343 7-3v-3c0 1.657-3.134 3-7 3s-7-1.343-7-3z"/><path d="M3 7v3c0 1.657 3.134 3 7 3s7-1.343 7-3V7c0 1.657-3.134 3-7 3S3 8.657 3 7z"/><path d="M17 5c0 1.657-3.134 3-7 3S3 6.657 3 5s3.134-3 7-3 7 1.343 7 3z"/></svg>
            </span>
            <div>
              <p className="ud-panel-label">Data Source</p>
              <p className="ud-panel-value">{tenantId || 'Not connected'}</p>
            </div>
          </div>
          <div className="ud-panel-row">
            <span className="ud-panel-icon">
              <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M5 4a3 3 0 00-3 3v6a3 3 0 003 3h10a3 3 0 003-3V7a3 3 0 00-3-3H5zm-1 9v-1h5v2H5a1 1 0 01-1-1zm7 1h4a1 1 0 001-1v-1h-5v2zm0-4h5V8h-5v2zM9 8H4v2h5V8z" clipRule="evenodd"/></svg>
            </span>
            <div>
              <p className="ud-panel-label">Tables Used</p>
              <p className="ud-panel-value">{availableTables.length} tables</p>
            </div>
          </div>
          <div className="ud-panel-row">
            <span className="ud-panel-icon">
              <svg viewBox="0 0 20 20" fill="currentColor"><path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clipRule="evenodd"/></svg>
            </span>
            <div>
              <p className="ud-panel-label">Last Updated</p>
              <p className="ud-panel-value ud-live-dot">● Live</p>
            </div>
          </div>
        </div>

        <div className="ud-panel-section">
          <p className="ud-panel-title">AI Reasoning</p>
          <p className="ud-panel-reasoning">
            {agentResult?.reasoning || 'Run Refresh Insights to see how the agent analysed your data.'}
          </p>
        </div>

        <div className="ud-panel-section">
          <p className="ud-panel-title">Confidence</p>
          <div className="ud-confidence-ring-wrap">
            <div className={`ud-confidence-ring ${agentResult ? 'has-data' : ''}`}
                 style={agentResult ? { borderColor: agentResult.confidence >= 70 ? '#34d399' : agentResult.confidence >= 40 ? '#fbbf24' : '#f87171' } : {}}>
              <span className="ud-confidence-value" style={agentResult ? { color: '#f0f5ff' } : {}}>
                {agentResult ? `${agentResult.confidence}%` : '—'}
              </span>
            </div>
            <p className="ud-confidence-label">
              {agentResult
                ? agentResult.confidence >= 70 ? 'High confidence' : agentResult.confidence >= 40 ? 'Medium confidence' : 'Low confidence'
                : 'Run analysis to see confidence score'}
            </p>
          </div>
        </div>

        <div className="ud-panel-section">
          <p className="ud-panel-title">Suggested Metrics</p>
          <div className="ud-chips">
            {['LTV', 'ARPU', 'Churn', 'CAC', 'Retention', 'NPS'].map((m) => (
              <span className="ud-chip" key={m}>{m}</span>
            ))}
          </div>
        </div>

        <div className="ud-panel-section">
          <p className="ud-panel-title">Follow-up Questions</p>
          <div className="ud-followup-list">
            {(agentResult?.followUpQuestions?.length > 0
              ? agentResult.followUpQuestions
              : ['Break down by acquisition channel', 'Show impact of campaigns', 'Analyze by user cohort', 'What about subscription type?']
            ).map((q) => (
              <button key={q} className="ud-followup-item" type="button" onClick={() => jumpToAsk(q)}>
                {q} ›
              </button>
            ))}
          </div>
        </div>

        <div className="ud-panel-section ud-profile-section">
          <p className="ud-panel-title">Profile</p>
          <div className="ud-profile-row">
            <p className="ud-panel-label">User ID</p>
            <p className="ud-panel-value">{userId || '—'}</p>
          </div>
          <div className="ud-profile-row">
            <p className="ud-panel-label">Position</p>
            <p className="ud-panel-value">{position || '—'}</p>
          </div>
          <div className="ud-profile-row">
            <p className="ud-panel-label">Schema</p>
            <p className="ud-panel-value">{tenantSchema || '—'}</p>
          </div>
        </div>

      </aside>
    </div>
  );
}

/* ─── NavItem component ─────────────────────── */
function NavItem({ icon, label, active, onClick, disabled }) {
  return (
    <button
      className={`ud-nav-item${active ? ' active' : ''}${disabled ? ' ud-nav-disabled' : ''}`}
      onClick={disabled ? undefined : onClick}
      type="button"
    >
      <Icon name={icon} />
      {label}
    </button>
  );
}

export default UserDashboardPage;
