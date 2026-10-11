import { useEffect, useMemo, useState } from 'react';
import ChartRenderer from './ChartRenderer';
import TableRenderer from './TableRenderer';

function formatRelativeTime(iso) {
  if (!iso) return '';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return '';
  const diff = Date.now() - t;
  const sec = Math.floor(diff / 1000);
  if (sec < 45) return 'Just now';
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day === 1) return 'Yesterday';
  if (day < 7) {
    return new Date(iso).toLocaleDateString(undefined, { weekday: 'short' });
  }
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function severityClass(card) {
  const badge = (card.badge || card.impactLevel || '').toUpperCase();
  if (badge === 'ALERT' || badge === 'HIGH' || badge === 'CRITICAL') return 'sev-critical';
  if (badge === 'RISK' || badge === 'MEDIUM') return 'sev-high';
  if (badge === 'OPPORTUNITY' || badge === 'POSITIVE') return 'sev-opportunity';
  return 'sev-info';
}

function categoryLabel(card) {
  const badge = (card.badge || '').toUpperCase();
  if (badge === 'ALERT') return 'Critical';
  if (badge === 'RISK') return 'Risk';
  if (badge === 'OPPORTUNITY') return 'Opportunity';
  if (badge === 'INFO') return 'Trend';
  const impact = (card.impactLevel || '').toUpperCase();
  if (impact === 'HIGH') return 'Critical';
  if (impact === 'MEDIUM') return 'Watch';
  return card.agentName || 'Insight';
}

function primaryMetric(card) {
  const highlights = card?.metricHighlights;
  if (!Array.isArray(highlights) || highlights.length === 0) return null;
  const withValue = highlights.find((h) => h?.value != null && String(h.value).trim() !== '');
  return withValue || null;
}

function isRenderableChart(chart) {
  if (!chart || typeof chart !== 'object') return false;
  const type = String(chart.type || '').toUpperCase();
  if (type === 'TABLE' || (Array.isArray(chart.columns) && Array.isArray(chart.rows))) {
    return (chart.columns?.length > 0 && chart.rows?.length > 0);
  }
  return Boolean(type && Array.isArray(chart.data) && chart.data.length > 0);
}

function resolveChart(card, chartItems) {
  if (isRenderableChart(card?.chart)) return card.chart;
  if (card?.id && chartItems?.length) {
    const match = chartItems.find((c) => c.insightId === card.id);
    if (isRenderableChart(match?.chart)) return match.chart;
  }
  return null;
}

function isTableSpec(spec) {
  if (!spec) return false;
  const type = String(spec.type || '').toUpperCase();
  return type === 'TABLE' || (Array.isArray(spec.columns) && Array.isArray(spec.rows));
}

function emptyFilterMessage(filter, hasAnyCards) {
  if (!hasAnyCards) {
    return {
      title: 'No new insights detected',
      body: 'Refresh Insights to analyse your approved catalogue for material, evidence-backed findings.',
      showRefresh: true,
    };
  }
  if (filter === 'unread') {
    return {
      title: 'No unread insights',
      body: 'You’re caught up. Switch to All to review previous findings.',
      showRefresh: false,
    };
  }
  return {
    title: 'No insights match this filter',
    body: 'Try another filter, or refresh for a new analysis.',
    showRefresh: false,
  };
}

/**
 * Two-pane executive Insights inbox — list + detail.
 * Read/unread is persisted via parent handlers → backend APIs.
 */
export default function InsightsInbox({
  cards,
  loading,
  error,
  dailyBrief,
  usingPersistedFeed,
  chartItems,
  onRefresh,
  onSelectRead,
  onMarkUnread,
  onDismiss,
  onAskKontexa,
}) {
  const [filter, setFilter] = useState('all');
  const [selectedId, setSelectedId] = useState(null);
  const [mobileDetail, setMobileDetail] = useState(false);

  const allCards = cards || [];

  const counts = useMemo(() => {
    const list = allCards;
    return {
      all: list.length,
      unread: list.filter((c) => c.unread).length,
      critical: list.filter((c) => {
        const b = (c.badge || c.impactLevel || '').toUpperCase();
        return b === 'ALERT' || b === 'HIGH' || b === 'CRITICAL' || b === 'RISK';
      }).length,
      risks: list.filter((c) => {
        const b = (c.badge || '').toUpperCase();
        return b === 'RISK' || b === 'ALERT';
      }).length,
      opportunities: list.filter((c) => (c.badge || '').toUpperCase() === 'OPPORTUNITY').length,
      trends: list.filter((c) => (c.badge || '').toUpperCase() === 'INFO'
        || (c.agentName || '').toLowerCase().includes('trend')).length,
    };
  }, [allCards]);

  const filtered = useMemo(() => {
    let list = allCards;
    if (filter === 'unread') list = list.filter((c) => c.unread);
    if (filter === 'critical') {
      list = list.filter((c) => {
        const b = (c.badge || c.impactLevel || '').toUpperCase();
        return b === 'ALERT' || b === 'HIGH' || b === 'CRITICAL' || b === 'RISK';
      });
    }
    if (filter === 'risks') {
      list = list.filter((c) => {
        const b = (c.badge || '').toUpperCase();
        return b === 'RISK' || b === 'ALERT';
      });
    }
    if (filter === 'opportunities') {
      list = list.filter((c) => (c.badge || '').toUpperCase() === 'OPPORTUNITY');
    }
    if (filter === 'trends') {
      list = list.filter((c) => (c.badge || '').toUpperCase() === 'INFO'
        || (c.agentName || '').toLowerCase().includes('trend'));
    }
    return list;
  }, [allCards, filter]);

  useEffect(() => {
    if (!filtered.length) {
      setSelectedId(null);
      return;
    }
    if (!selectedId || !filtered.some((c) => (c.id || c.title) === selectedId)) {
      // Desktop: keep a selection. Mobile stays on list until user taps (mobileDetail).
      setSelectedId(filtered[0].id || filtered[0].title);
    }
  }, [filtered, selectedId]);

  const selected = filtered.find((c) => (c.id || c.title) === selectedId) || null;
  const chartSpec = selected ? resolveChart(selected, chartItems) : null;
  const emptyMsg = emptyFilterMessage(filter, allCards.length > 0);

  const selectCard = (card) => {
    const key = card.id || card.title;
    setSelectedId(key);
    setMobileDetail(true);
    if (card.unread && card.id && onSelectRead) {
      onSelectRead(card);
    }
  };

  const filters = [
    ['all', 'All', counts.all],
    ['unread', 'Unread', counts.unread],
    ['critical', 'Critical', counts.critical],
    ['risks', 'Risks', counts.risks],
    ['opportunities', 'Opportunities', counts.opportunities],
    ['trends', 'Trends', counts.trends],
  ];

  return (
    <div className="insights-inbox">
      <div className={`insights-inbox-list ${mobileDetail ? 'hidden-mobile' : ''}`}>
        <div className="insights-inbox-toolbar">
          <div className="insights-inbox-toolbar-head">
            <span className="insights-inbox-label">Inbox</span>
            {counts.unread > 0 ? (
              <span className="insights-unread-count">{counts.unread} unread</span>
            ) : (
              <span className="insights-inbox-caught-up">Caught up</span>
            )}
          </div>
          <div className="insights-filter-row" role="tablist" aria-label="Insight filters">
            {filters.map(([id, label, count]) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={filter === id}
                className={`insights-filter-chip ${filter === id ? 'active' : ''}`}
                onClick={() => setFilter(id)}
              >
                {label}
                <span className="insights-filter-count">{count}</span>
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="insights-state insights-state--loading">
            <div className="feed-loading-dots"><span /><span /><span /></div>
            <p>Scanning for evidence-backed insights…</p>
          </div>
        ) : null}

        {error && !loading ? (
          <div className="insights-state insights-state--error" role="alert">
            <h2>Unable to load insights</h2>
            <p>{error}</p>
            <button className="ic-btn ic-btn--primary" type="button" onClick={onRefresh}>
              Retry
            </button>
          </div>
        ) : null}

        {dailyBrief && !loading && !error ? (
          <div className="insights-brief">
            <p className="insights-brief-label">Leadership brief</p>
            <p className="insights-brief-text">{dailyBrief}</p>
          </div>
        ) : null}

        {usingPersistedFeed && !loading && !error && allCards.length > 0 ? (
          <div className="insights-persisted-hint">
            Saved insights · <button type="button" className="insights-inline-link" onClick={onRefresh}>
              Refresh Insights
            </button>
            {' '}for a new analysis
          </div>
        ) : null}

        {!loading && !error && filtered.length === 0 ? (
          <div className="insights-state insights-state--empty">
            <div className="insights-empty-glyph" aria-hidden>◈</div>
            <h2>{emptyMsg.title}</h2>
            <p>{emptyMsg.body}</p>
            {emptyMsg.showRefresh ? (
              <button className="ic-btn ic-btn--primary" type="button" onClick={onRefresh}>
                Refresh Insights
              </button>
            ) : null}
          </div>
        ) : null}

        {!loading && !error && filtered.length > 0 ? (
          <ul className="insights-row-list" role="listbox" aria-label="Insights">
            {filtered.map((card) => {
              const key = card.id || card.title;
              const active = key === selectedId;
              const metric = primaryMetric(card);
              return (
                <li key={key}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={active}
                    className={[
                      'insights-row',
                      card.unread ? 'unread' : 'read',
                      active ? 'selected' : '',
                    ].filter(Boolean).join(' ')}
                    onClick={() => selectCard(card)}
                  >
                    <span className={`insights-sev ${severityClass(card)}`} aria-hidden />
                    <span className="insights-row-body">
                      <span className="insights-row-top">
                        <span className="insights-row-title">{card.title}</span>
                        <span className="insights-row-time">
                          {formatRelativeTime(card.generatedAt)}
                        </span>
                      </span>
                      <span className="insights-row-preview">
                        {card.description || categoryLabel(card)}
                      </span>
                      <span className="insights-row-meta">
                        <span className={`insights-pill ${severityClass(card)}`}>
                          {categoryLabel(card)}
                        </span>
                        {metric?.value ? (
                          <span className="insights-row-metric" title={metric.label || ''}>
                            {metric.value}
                          </span>
                        ) : null}
                      </span>
                    </span>
                    {card.unread ? (
                      <span className="insights-unread-dot" title="Unread" aria-label="Unread" />
                    ) : null}
                  </button>
                </li>
              );
            })}
          </ul>
        ) : null}
      </div>

      <div
        className={`insights-inbox-detail ${mobileDetail ? 'show-mobile' : 'hidden-mobile-empty'}`}
      >
        {selected && !loading ? (
          <>
            <button
              type="button"
              className="insights-back-btn"
              onClick={() => setMobileDetail(false)}
            >
              ← Inbox
            </button>

            <header className="insights-detail-head">
              <div className="insights-detail-badges">
                <span className={`insights-pill ${severityClass(selected)}`}>
                  {categoryLabel(selected)}
                </span>
                {selected.unread ? (
                  <span className="insights-pill insights-pill--unread">Unread</span>
                ) : (
                  <span className="insights-pill insights-pill--muted">Read</span>
                )}
                {selected.agentName ? (
                  <span className="insights-pill insights-pill--muted">{selected.agentName}</span>
                ) : null}
              </div>
              <h2 className="insights-detail-title">{selected.title}</h2>
              <p className="insights-detail-sub">
                Detected {formatRelativeTime(selected.generatedAt) || 'recently'}
                {selected.confidence != null ? ` · ${selected.confidence}% confidence` : ''}
              </p>
            </header>

            {selected.description ? (
              <section className="insights-detail-section">
                <h3>Executive summary</h3>
                <p>{selected.description}</p>
              </section>
            ) : null}

            {selected.metricHighlights?.length > 0 ? (
              <section className="insights-detail-section">
                <h3>Key numbers</h3>
                <div className="ic-metrics">
                  {selected.metricHighlights.map((m, i) => (
                    <div className="ic-metric-chip" key={i}>
                      <span className="ic-metric-value">{m.value}</span>
                      <span className="ic-metric-label">{m.label}</span>
                    </div>
                  ))}
                </div>
              </section>
            ) : null}

            {chartSpec ? (
              <section className="insights-detail-section">
                <h3>{isTableSpec(chartSpec) ? 'Table' : 'Visualization'}</h3>
                {chartSpec.title && !isTableSpec(chartSpec) ? (
                  <p className="insights-chart-caption">{chartSpec.title}</p>
                ) : null}
                <div className={isTableSpec(chartSpec) ? 'insights-detail-table' : 'insights-detail-chart'}>
                  {isTableSpec(chartSpec) ? (
                    <TableRenderer spec={chartSpec} />
                  ) : (
                    <ChartRenderer spec={chartSpec} />
                  )}
                </div>
              </section>
            ) : null}

            {selected.reasons?.length > 0 ? (
              <section className="insights-detail-section">
                <h3>Supporting evidence</h3>
                <ul className="ic-reason-list">
                  {selected.reasons.map((r, i) => <li key={i}>{r}</li>)}
                </ul>
              </section>
            ) : null}

            {selected.interpretation ? (
              <section className="insights-detail-section">
                <h3>Interpretation</h3>
                <p>{selected.interpretation}</p>
              </section>
            ) : null}

            {selected.sourceColumns?.length > 0 ? (
              <section className="insights-detail-section">
                <h3>Sources</h3>
                <div className="insights-source-chips">
                  {selected.sourceColumns.map((col, i) => (
                    <span className="insights-source-chip" key={i}>{col}</span>
                  ))}
                </div>
              </section>
            ) : null}

            {selected.strategies?.length > 0 ? (
              <section className="insights-detail-section">
                <h3>Recommended next step</h3>
                <ul className="ic-strategy-list">
                  {selected.strategies.map((s, i) => <li key={i}>{s}</li>)}
                </ul>
              </section>
            ) : null}

            <div className="insights-detail-actions">
              <button
                type="button"
                className="ic-btn ic-btn--primary"
                onClick={() => onAskKontexa?.(
                  `Investigate further: ${selected.title}. ${selected.description || ''}`
                )}
              >
                Ask Tracefable
              </button>
              {selected.unread ? (
                <button
                  type="button"
                  className="ic-btn"
                  onClick={() => onSelectRead?.(selected)}
                >
                  Mark read
                </button>
              ) : (
                <button
                  type="button"
                  className="ic-btn"
                  onClick={() => onMarkUnread?.(selected)}
                >
                  Mark unread
                </button>
              )}
              <button
                type="button"
                className="ic-btn ic-btn--danger"
                onClick={() => {
                  onDismiss?.(selected);
                  setMobileDetail(false);
                }}
              >
                Dismiss
              </button>
            </div>
          </>
        ) : (
          <div className="insights-detail-empty">
            {loading ? (
              <>
                <div className="feed-loading-dots"><span /><span /><span /></div>
                <p>Loading insight detail…</p>
              </>
            ) : (
              <>
                <div className="insights-empty-glyph" aria-hidden>◈</div>
                <p>Select an insight to review evidence and next steps.</p>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
