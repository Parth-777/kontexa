import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import GetAccessModal from '../components/GetAccessModal';

const OUTCOMES = [
  {
    symbol: '◆',
    title: 'Revenue concentration risk',
    body: 'Identify which segments, regions, or products drive disproportionate revenue — before it becomes a structural dependency.',
  },
  {
    symbol: '⊕',
    title: 'Performance anomaly detection',
    body: 'Surface what changed, when it changed, and which dimension drove the deviation — without building a dashboard.',
  },
  {
    symbol: '△',
    title: 'Growth driver attribution',
    body: 'Decompose growth or decline into ranked contributors with quantified impact. Know what actually moved the number.',
  },
  {
    symbol: '◈',
    title: 'Executive-grade findings',
    body: 'Structured conclusions with ranked evidence. Contribution breakdowns, efficiency rankings, temporal patterns — ready for decisions.',
  },
];

const TRUST_ITEMS = [
  { symbol: '▤', label: 'Evidence-grounded', sub: 'Every finding is computed, not narrated' },
  { symbol: '◈', label: 'Multi-warehouse',   sub: 'BigQuery-native, expanding to more' },
  { symbol: '◉', label: 'Traceable logic',   sub: 'Every conclusion links to its source' },
  { symbol: '△', label: 'Structured output', sub: 'Ranked findings, not raw summaries' },
];

function HomePage() {
  const questions = useMemo(
    () => [
      'Which regions are driving margin decline this quarter?',
      'Why did user retention drop after the Q3 product update?',
      'Which customer segments show the highest concentration risk?',
      'What changed before conversion rates declined in Europe?',
      'Where is revenue growth concentrated across product lines?',
    ],
    []
  );

  const [questionIndex, setQuestionIndex] = useState(0);
  const [typedText,     setTypedText]     = useState('');
  const [isDeleting,    setIsDeleting]    = useState(false);
  const [accessOpen,    setAccessOpen]    = useState(false);
  const [accessSource,  setAccessSource]  = useState('homepage-hero');

  useEffect(() => {
    const current   = questions[questionIndex];
    const doneTyping   = !isDeleting && typedText === current;
    const doneDeleting = isDeleting  && typedText === '';
    const delay = doneTyping ? 1800 : isDeleting ? 28 : 42;

    const timer = window.setTimeout(() => {
      if (doneTyping)   { setIsDeleting(true); return; }
      if (doneDeleting) {
        setIsDeleting(false);
        setQuestionIndex((p) => (p + 1) % questions.length);
        return;
      }
      const next = typedText.length + (isDeleting ? -1 : 1);
      setTypedText(current.slice(0, next));
    }, delay);

    return () => window.clearTimeout(timer);
  }, [typedText, isDeleting, questionIndex, questions]);

  return (
    <div className="hp-shell">
      {/* background atmosphere */}
      <div className="hp-orb hp-orb--a" />
      <div className="hp-orb hp-orb--b" />
      <div className="hp-orb hp-orb--c" />
      <div className="hp-grid" />

      {/* ── Nav ───────────────────────────────────────────────────────── */}
      <nav className="hp-nav">
        <div className="hp-nav-inner">
          <Link to="/" className="hp-nav-brand">
            <span className="hp-nav-logo">K</span>
            <span className="hp-nav-wordmark">KONTEXA</span>
          </Link>
          <div className="hp-nav-links">
            <a href="#outcomes">Platform</a>
            <a href="#trust">Enterprise</a>
            <Link to="/signin" className="hp-nav-cta">Sign in</Link>
          </div>
        </div>
      </nav>

      {/* ── Hero ──────────────────────────────────────────────────────── */}
      <section className="hp-hero">
        <p className="hp-hero-eyebrow">Decision Intelligence Platform</p>
        <h1 className="hp-hero-h1">
          Understand what changed.<br />
          <span className="hp-hero-h1-accent">Act on what matters.</span>
        </h1>
        <p className="hp-hero-sub">
          Kontexa converts warehouse data into structured analytical intelligence —
          ranked findings, contribution breakdowns, and executive narratives.
          
        </p>

        <div className="hp-query-panel">
          <div className="hp-query-label">Ask a question</div>
          <div className="hp-query-box">
            <span className="hp-query-arrow">›</span>
            <span className="hp-query-text">{typedText || '\u00A0'}</span>
            <span className="hp-query-cursor" />
          </div>
        </div>

        <div className="hp-hero-actions">
          <button
            type="button"
            className="hp-btn hp-btn--primary"
            onClick={() => { setAccessSource('homepage-hero'); setAccessOpen(true); }}
          >
            Get access
          </button>
          <a href="#preview" className="hp-btn hp-btn--ghost">
            See an example →
          </a>
        </div>
      </section>

      {/* ── Product preview ───────────────────────────────────────────── */}
      <section id="preview" className="hp-preview-section">
        <div className="hp-preview-label">What Kontexa actually produces</div>
        <div className="hp-preview-card">
          <div className="hp-preview-question">
            <span className="hp-preview-q-icon">›</span>
            Which regions are driving margin decline this quarter?
          </div>

          <div className="hp-preview-body">
            <div className="hp-preview-head-row">
              <span className="hp-finding-tag">CONTRIBUTION_BREAKDOWN</span>
              <span className="hp-preview-title">Revenue by Region · Q4 2025</span>
            </div>

            <div className="hp-segments">
              <div className="hp-segment">
                <span className="hp-seg-rank">#1</span>
                <span className="hp-seg-name">North America</span>
                <div className="hp-seg-bar-wrap">
                  <div className="hp-seg-bar" style={{ width: '100%' }} />
                </div>
                <span className="hp-seg-val">$4.2M</span>
                <span className="hp-seg-pct">68%</span>
                <span className="hp-seg-delta hp-delta--up">▲ +4%</span>
              </div>
              <div className="hp-segment hp-segment--flagged">
                <span className="hp-seg-rank">#2</span>
                <span className="hp-seg-name">Europe</span>
                <div className="hp-seg-bar-wrap">
                  <div className="hp-seg-bar hp-seg-bar--warn" style={{ width: '26%' }} />
                </div>
                <span className="hp-seg-val">$1.1M</span>
                <span className="hp-seg-pct">18%</span>
                <span className="hp-seg-delta hp-delta--down">▼ −31%</span>
              </div>
              <div className="hp-segment">
                <span className="hp-seg-rank">#3</span>
                <span className="hp-seg-name">APAC</span>
                <div className="hp-seg-bar-wrap">
                  <div className="hp-seg-bar" style={{ width: '15%' }} />
                </div>
                <span className="hp-seg-val">$0.6M</span>
                <span className="hp-seg-pct">10%</span>
                <span className="hp-seg-delta hp-delta--up">▲ +8%</span>
              </div>
              <div className="hp-segment hp-segment--muted">
                <span className="hp-seg-rank">—</span>
                <span className="hp-seg-name">Other</span>
                <div className="hp-seg-bar-wrap">
                  <div className="hp-seg-bar hp-seg-bar--muted" style={{ width: '6%' }} />
                </div>
                <span className="hp-seg-val">$0.2M</span>
                <span className="hp-seg-pct">4%</span>
                <span className="hp-seg-delta hp-delta--flat">▼ −5%</span>
              </div>
            </div>

            <div className="hp-preview-divider" />
            <div className="hp-preview-finding">
              <span className="hp-finding-label">Finding</span>
              Europe shows <strong>31% margin compression</strong> QoQ — the primary driver of total decline at 18% revenue share.
              Top-3 concentration: <strong>96%</strong> · Leader gap: <strong>3.8× median</strong> · Gini: 0.72
            </div>
          </div>
        </div>
      </section>

      {/* ── Outcomes ──────────────────────────────────────────────────── */}
      <section id="outcomes" className="hp-outcomes">
        <div className="hp-section-header">
          <p className="hp-section-eyebrow">Platform capabilities</p>
          <h2 className="hp-section-title">Built for operational intelligence</h2>
          <p className="hp-section-sub">
            Kontexa moves analysis from reactive to structural — from charts to conclusions.
          </p>
        </div>
        <div className="hp-outcomes-grid">
          {OUTCOMES.map((o) => (
            <div key={o.title} className="hp-outcome-card">
              <span className="hp-outcome-symbol">{o.symbol}</span>
              <h3 className="hp-outcome-title">{o.title}</h3>
              <p className="hp-outcome-body">{o.body}</p>
            </div>
          ))}
        </div>
      </section>

      {/* ── Enterprise trust ──────────────────────────────────────────── */}
      <section id="trust" className="hp-trust">
        <div className="hp-trust-inner">
          <p className="hp-section-eyebrow" style={{ textAlign: 'center' }}>Enterprise-grade</p>
          <h2 className="hp-trust-title">Built for enterprise analytical workloads</h2>
          <p className="hp-trust-sub">
            No hallucinated insights. No generic summaries. Every finding is computed from
            structured evidence, grounded in your warehouse data.
          </p>
          <div className="hp-trust-grid">
            {TRUST_ITEMS.map((t) => (
              <div key={t.label} className="hp-trust-item">
                <span className="hp-trust-symbol">{t.symbol}</span>
                <div>
                  <p className="hp-trust-label">{t.label}</p>
                  <p className="hp-trust-detail">{t.sub}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* ── Final CTA ─────────────────────────────────────────────────── */}
      <section className="hp-final-cta">
        <p className="hp-section-eyebrow">Get started</p>
        <h2 className="hp-final-title">
          Your data has answers.<br />Kontexa surfaces them.
        </h2>
        <p className="hp-final-sub">
          Connect your warehouse and start asking questions that move decisions forward.
        </p>
        <button
          type="button"
          className="hp-btn hp-btn--primary hp-btn--lg"
          onClick={() => { setAccessSource('homepage-cta'); setAccessOpen(true); }}
        >
          Access the platform
        </button>
      </section>

      <GetAccessModal
        isOpen={accessOpen}
        onClose={() => setAccessOpen(false)}
        sourcePage={accessSource}
      />

      {/* ── Footer ────────────────────────────────────────────────────── */}
      <footer className="hp-footer">
        <div className="hp-footer-inner">
          <div className="hp-footer-brand">
            <span className="hp-nav-logo hp-nav-logo--sm">K</span>
            <span className="hp-nav-wordmark hp-nav-wordmark--muted">KONTEXA</span>
          </div>
          <span className="hp-footer-copy">© 2026 Kontexa. All rights reserved.</span>
        </div>
      </footer>
    </div>
  );
}

export default HomePage;
