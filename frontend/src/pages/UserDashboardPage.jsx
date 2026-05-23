import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { askQuestion, fetchTablesForSchema, getPersistedInsights, runAgentDashboard, updateInsightStatus } from '../api/queryApi';
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
    generatedAt:      entity.generatedAt,
    status:           entity.status,
    persisted:        true,
  };
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

/** Stable key for hiding cards/anomalies from the feed */
function feedKey(item) {
  if (item.id) return item.id;
  if (item.metric) return `anomaly:${item.metric}`;
  return item.title;
}

/* ─── Shared card footer ───────────────────────────────────── */
function CardFooter({ onMarkAsRead, onDismiss }) {
  return (
    <div className="ic-card-footer">
      <button className="ic-read-btn" type="button" onClick={onMarkAsRead}>
        ✓ Mark as read
      </button>
      <button className="ic-dismiss-btn" type="button" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

/* ─── Insight card ─────────────────────────────────────────── */
function InsightCard({ card, onMarkAsRead, onDismiss }) {
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

/* ─── Main page ────────────────────────────────────────────── */
function UserDashboardPage() {
  const navigate = useNavigate();
  const session      = JSON.parse(window.sessionStorage.getItem('kontexaUserSession') || '{}');
  const userId       = session.userId       || '';
  const tenantId     = session.tenantId     || '';
  const tenantSchema = session.tenantSchema || '';

  /* ── Agent state ──────────────────────────── */
  const [agentResult,  setAgentResult]  = useState(null);
  const [agentRunning, setAgentRunning] = useState(false);
  const [agentError,   setAgentError]   = useState('');
  const [dismissed,    setDismissed]    = useState(new Set());
  const [feedCards,    setFeedCards]    = useState([]);   // persisted cards loaded on mount
  const [feedLoading,  setFeedLoading]  = useState(true);
  const [feedFollowUp, setFeedFollowUp] = useState([]);  // suggested questions for persisted feed

  /* ── Chat state ───────────────────────────── */
  const [messages,    setMessages]    = useState([
    { id: 0, role: 'ai', text: "Hi! I'm your AI analyst. Ask me anything about your data, or click Refresh Insights to let me scan for patterns automatically." }
  ]);
  const [chatInput,   setChatInput]   = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const messagesEndRef = useRef(null);

  /* ── Tables (for chat context) ────────────── */
  const [availableTables, setAvailableTables] = useState([]);

  /* ── Load persisted cards + follow-up questions on mount ─────────── */
  useEffect(() => {
    if (!tenantSchema) { setFeedLoading(false); return; }
    const BASE = process.env.REACT_APP_API_BASE || 'http://localhost:8080';

    getPersistedInsights(tenantSchema)
      .then(entities => {
        const cards = (entities || []).map(normalizePersistedCard);
        setFeedCards(cards);
        // Load follow-up questions for the persisted feed
        if (cards.length > 0) {
          fetch(`${BASE}/api/agent/insights/followup?clientId=${tenantSchema}`)
            .then(r => r.ok ? r.json() : { followUpQuestions: [] })
            .then(d => setFeedFollowUp(d.followUpQuestions || []))
            .catch(() => {});
        }
      })
      .catch(() => {})
      .finally(() => setFeedLoading(false));
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
        const BASE = process.env.REACT_APP_API_BASE || 'http://localhost:8080';
        getPersistedInsights(tenantSchema)
          .then(entities => setFeedCards((entities || []).map(normalizePersistedCard)))
          .catch(() => {});
        // Refresh follow-up questions from the new batch
        fetch(`${BASE}/api/agent/insights/followup?clientId=${tenantSchema}`)
          .then(r => r.ok ? r.json() : { followUpQuestions: [] })
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
      const payload = await askQuestion(tenantSchema, text, overrideText != null);

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

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendChat();
    }
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

  /** Not relevant — hide from feed; persist when card has a database id */
  const handleDismiss = async (item) => {
    removeFromFeed(item);
    if (item.id) {
      updateInsightStatus(tenantSchema, item.id, 'DECLINED').catch(() => {});
    }
  };

  /* ── Jump to chat with prefilled question ─── */
  const handleAsk = (question) => {
    handleSendChat(question);
  };

  /* ── Sign out ─────────────────────────────── */
  const handleSignOut = () => {
    window.sessionStorage.removeItem('kontexaUserSession');
    navigate('/signin');
  };

  /* ── Visible cards ────────────────────────── */
  // After a fresh run use the live result; otherwise fall back to persisted feed
  const isVisible = (item) => !dismissed.has(feedKey(item));
  const liveInsights     = (agentResult?.insights  || []).filter(isVisible);
  const liveAnomalies    = (agentResult?.anomalies || []).filter(isVisible);
  const persistedVisible = agentResult ? [] : feedCards.filter(isVisible);
  const visibleInsights  = agentResult ? liveInsights  : persistedVisible;
  const visibleAnomalies = agentResult ? liveAnomalies : [];
  const totalCards       = visibleInsights.length + visibleAnomalies.length;
  const usingPersistedFeed = !agentResult && persistedVisible.length > 0;

  /* ══════════════════════════════════════════ */
  return (
    <div className="inbox-shell">

      {/* ── TOP BAR ───────────────────────────── */}
      <header className="inbox-topbar">
        <div className="inbox-topbar-left">
          <span className="inbox-logo"> KONTEXA</span>
          <span className="inbox-divider" />
          <span className="inbox-page-title">Agent Feed</span>
          {totalCards > 0 && (
            <span className="inbox-count-badge">{totalCards}</span>
          )}
        </div>
        <div className="inbox-topbar-right">
          <span className="inbox-user-pill">
            <span className="inbox-user-avatar">{(userId || 'U')[0].toUpperCase()}</span>
            <span>{userId}</span>
          </span>
          <button
            className={`inbox-refresh-btn ${agentRunning ? 'loading' : ''}`}
            type="button"
            onClick={handleRefresh}
            disabled={agentRunning}
          >
            {agentRunning ? (
              <><span className="inbox-spinner" /> Analysing...</>
            ) : (
              '⟳ Refresh Insights'
            )}
          </button>
          <button className="inbox-signout-btn" type="button" onClick={handleSignOut}>
            Sign Out
          </button>
        </div>
      </header>

      {/* ── BODY ──────────────────────────────── */}
      <div className="inbox-body">

        {/* ── AGENT FEED ──────────────────────── */}
        <main className="inbox-feed">

          {/* Loading state */}
          {agentRunning && (
            <div className="feed-loading">
              <div className="feed-loading-dots"><span/><span/><span/></div>
              <p>Agent is scanning your data for insights, trends, and anomalies...</p>
            </div>
          )}

          {/* Error state */}
          {agentError && !agentRunning && (
            <div className="feed-error">
              <p>⚠️ {agentError}</p>
            </div>
          )}

          {/* Persisted feed banner */}
          {usingPersistedFeed && (
            <div className="feed-persisted-banner">
              Showing saved insights · Click <strong>Refresh Insights</strong> to run a new analysis
            </div>
          )}

          {/* Empty state */}
          {!agentRunning && !agentError && totalCards === 0 && !feedLoading && (
            <div className="feed-empty">
              <div className="feed-empty-icon">🤖</div>
              <h2>No insights yet</h2>
              <p>Click <strong>Refresh Insights</strong> to let the agent scan your data for trends, anomalies, and opportunities.</p>
              <button className="feed-empty-btn" type="button" onClick={handleRefresh}>
                Run Analysis
              </button>
            </div>
          )}

          {/* Insight cards */}
          {visibleInsights.map((card, i) => (
            <InsightCard
              key={card.id || i}
              card={card}
              onMarkAsRead={handleMarkAsRead}
              onDismiss={handleDismiss}
            />
          ))}

          {/* Anomaly cards from the main agent */}
          {visibleAnomalies.map((a, i) => (
            <AnomalyCard
              key={feedKey(a)}
              anomaly={a}
              onMarkAsRead={handleMarkAsRead}
              onDismiss={handleDismiss}
            />
          ))}

          {/* Follow-up suggestions — always shown when there are cards */}
          {(() => {
            const questions = agentResult?.followUpQuestions?.length > 0
              ? agentResult.followUpQuestions
              : feedFollowUp;
            return questions.length > 0 && totalCards > 0 ? (
              <div className="feed-suggestions">
                <p className="feed-suggestions-label">Suggested questions</p>
                <div className="feed-suggestion-chips">
                  {questions.map((q, i) => (
                    <button key={i} className="feed-suggestion-chip" type="button"
                      onClick={() => handleAsk(q)}>
                      {q}
                    </button>
                  ))}
                </div>
              </div>
            ) : null;
          })()}
        </main>

        {/* ── CHAT PANEL ──────────────────────── */}
        <aside className="inbox-chat">
          <div className="chat-header">
            <span className="chat-header-title">
              <span className="chat-header-dot" />
              AI Chat
            </span>
            <span className="chat-header-sub">{tenantId} · {availableTables.length} tables</span>
          </div>

          <div className="chat-messages-wrap">
            {messages.map(msg => (
              <ChatMessage key={msg.id} msg={msg} onAsk={handleAsk} />
            ))}
            <div ref={messagesEndRef} />
          </div>

          <div className="chat-input-area">
            <textarea
              className="chat-textarea"
              value={chatInput}
              onChange={e => setChatInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask anything about your data..."
              rows={2}
              disabled={chatLoading}
            />
            <button
              className="chat-send-btn"
              type="button"
              onClick={() => handleSendChat()}
              disabled={chatLoading || !chatInput.trim()}
            >
              {chatLoading ? '...' : '↑'}
            </button>
          </div>
        </aside>

      </div>
    </div>
  );
}

export default UserDashboardPage;
