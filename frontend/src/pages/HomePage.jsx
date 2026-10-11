import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import GetAccessModal from '../components/GetAccessModal';
import HeroCanvas from '../components/homepage/HeroCanvas';
import ProblemShiftExperience from '../components/homepage/ProblemShiftExperience';
import InvestigationExperience from '../components/homepage/InvestigationExperience';
import ProductFindingExperience from '../components/homepage/ProductFindingExperience';
import FinalExperience from '../components/homepage/FinalExperience';


const connectors = [
  { name: 'Snowflake', mark: '❄' },
  { name: 'BigQuery', mark: '◈' },
  { name: 'Redshift', mark: '▮▮' },
  { name: 'Databricks', mark: '◆' },
  { name: 'PostgreSQL', mark: '◉' },
  { name: 'More', mark: '•••' },
];

const insightItems = [
  {
    tone: 'risk',
    title: 'Europe is the primary contributor to the revenue decline.',
    tag: 'Risk',
    meta: 'Europe · 2h ago',
  },
  {
    tone: 'risk',
    title: 'Enterprise segment churn increased in APAC.',
    tag: 'Risk',
    meta: 'Churn · 4h ago',
  },
  {
    tone: 'opportunity',
    title: 'Top growing product is driven by enterprise wins.',
    tag: 'Opportunity',
    meta: 'Growth · 1d ago',
  },
  {
    tone: 'watch',
    title: 'Support-ticket volume correlates with retention drop.',
    tag: 'Watch',
    meta: 'Retention · 2d ago',
  },
];

function Arrow() {
  return (
    <span className="mk-arrow" aria-hidden="true">
      →
    </span>
  );
}

function HomePage() {
  const [accessOpen, setAccessOpen] = useState(false);
  const [accessSource, setAccessSource] = useState('homepage-hero');

  const openAccess = (source) => {
    setAccessSource(source);
    setAccessOpen(true);
  };

  return (
    <div className="hp-shell">
      <div className="hp-bg-glow hp-bg-glow--one" />
      <div className="hp-bg-glow hp-bg-glow--two" />
      <div className="hp-grid" />

      <nav className="hp-nav">
        <div className="hp-nav-inner">
          <Link
            to="/"
            className="hp-nav-brand"
            aria-label="Tracefable home"
          >
            <span className="hp-nav-logo">K</span>
            <span className="hp-nav-wordmark">TRACEFABLE</span>
          </Link>

          <div className="hp-nav-links">
            <a href="#problem">Problem</a>
            <a href="#how">How it works</a>
            <a href="#insights">Insights</a>
            <a href="#evidence">Evidence</a>
            <a href="#connectors">Connectors</a>

            <button
              className="hp-nav-access"
              onClick={() => openAccess('homepage-nav')}
            >
              Request access
            </button>

            <Link
              to="/signin"
              className="hp-nav-signin"
            >
              Sign in
            </Link>
          </div>
        </div>
      </nav>

      <main>

        {/* =========================================================
            HERO
        ========================================================= */}

        <section className="mk-hero">
          <div className="mk-hero-copy">
            <p className="mk-eyebrow">
              Evidence-driven · warehouse-native
            </p>

            <h1>
              Business Intelligence <span>Made Easy</span>
            </h1>

            <p className="mk-hero-lede">
              Ask business questions in plain English. Tracefable analyzes your
              data, explains what changed, and surfaces the insights that
              matter.
            </p>

            <div className="mk-hero-actions">
              <button
                className="hp-btn hp-btn--primary"
                onClick={() => openAccess('homepage-hero')}
              >
                Request enterprise access
              </button>

              <a
                className="hp-btn hp-btn--ghost"
                href="#how"
              >
                See how it works <Arrow />
              </a>
            </div>

            <div className="mk-hero-trust">
              <span>
                <b>✦</b> Works inside Claude
              </span>

              <span>
                <b>◇</b> Enterprise-grade security
              </span>

              <span>
                <b>▤</b> Your data stays in your warehouse
              </span>
            </div>
          </div>

          <div className="mk-claude-wrap">
            <div className="mk-claude-label">
              <span className="mk-claude-dot">✦</span>

              <strong>Tracefable + Claude</strong>

              <span className="mk-live-pill">
                Integrated inside Claude
              </span>

              <span className="mk-claude-name">
                Claude
              </span>
            </div>

            <div className="mk-claude-frame">
              <img
                src="/claude-kontexa-hero.png"
                alt="Tracefable working inside Claude"
              />

              <div className="mk-image-caption">
                Business question → warehouse analysis → evidence-backed answer
              </div>
            </div>
          </div>
        </section>


        {/* =========================================================
            PROBLEM / LOOP
        ========================================================= */}

        <section
          id="problem"
          className="mk-section mk-problem"
        >
          <div className="mk-section-head centered">
            <p className="mk-eyebrow">
              The problem
            </p>

            <h2>
              The bottleneck isn&apos;t data. <span>It&apos;s the loop.</span>
            </h2>

            <p>
              Important questions still move through a manual analytics relay
              before anyone can make a decision.
            </p>
          </div>

          <div className="mk-compare">

            <div className="mk-old-way">
              <div className="mk-compare-title">
                <span>THE OLD WAY</span>
                <em>Days or weeks</em>
              </div>

              <div className="mk-loop">
                <div className="mk-loop-node mk-loop-node--top">
                  Business question
                </div>

                <div className="mk-loop-node mk-loop-node--right">
                  Dashboard
                </div>

                <div className="mk-loop-node mk-loop-node--bottom">
                  Filter &amp; compare
                </div>

                <div className="mk-loop-node mk-loop-node--left">
                  Analyst / ticket
                </div>

                <div className="mk-loop-center">
                  ↻
                  <small>
                    Investigate
                    <br />
                    again
                  </small>
                </div>

                <i className="mk-loop-arrow mk-loop-arrow--a">
                  ↘
                </i>

                <i className="mk-loop-arrow mk-loop-arrow--b">
                  ↙
                </i>

                <i className="mk-loop-arrow mk-loop-arrow--c">
                  ↖
                </i>

                <i className="mk-loop-arrow mk-loop-arrow--d">
                  ↗
                </i>
              </div>
            </div>

            <div className="mk-vs">
              VS
            </div>

            <div className="mk-new-way">
              <div className="mk-compare-title">
                <span>WITH TRACEFABLE</span>
                <em>Minutes, not weeks</em>
              </div>

              <div className="mk-process">
                {[
                  ['01', 'Ask', 'plain English'],
                  ['02', 'Analyze', 'your warehouse'],
                  ['03', 'Explain', 'what changed'],
                  ['04', 'Evidence', 'you can trust'],
                  ['05', 'Decide', 'with confidence'],
                ].map(([num, title, sub], i) => (
                  <div
                    className="mk-process-item"
                    key={num}
                  >
                    <div className="mk-process-icon">
                      {num}
                    </div>

                    <strong>{title}</strong>

                    <span>{sub}</span>

                    {i < 4 && <Arrow />}
                  </div>
                ))}
              </div>

              <div className="mk-win-row">
                <span>✓ Fewer handoffs</span>
                <span>✓ Evidence before narrative</span>
                <span>✓ Confidence + unknowns</span>
              </div>
            </div>
          </div>
        </section>


        {/* =========================================================
            INSIGHTS
        ========================================================= */}

        <section
          id="insights"
          className="mk-section mk-insights"
        >
          <div className="mk-section-head split-head">
            <div>
              <p className="mk-eyebrow">
                Insights that matter
              </p>

              <h2>
                Surface what matters.
                <br />
                <span>Instantly.</span>
              </h2>
            </div>

            <p>
              A living inbox of meaningful changes, risks and opportunities —
              with the evidence behind every insight.
            </p>
          </div>

          <div className="mk-insight-shell">

            <div className="mk-insight-sidebar">
              <div className="mk-insight-top">
                <strong>Insights</strong>
                <span>12 unread</span>
              </div>

              <div className="mk-insight-tabs">
                <b>All</b>
                <span>Unread</span>
                <span>Critical</span>
                <span>Risks</span>
                <span>Opportunities</span>
              </div>

              <div className="mk-insight-list">
                {insightItems.map((item, index) => (
                  <div
                    className={`mk-insight-item ${
                      index === 0 ? 'is-selected' : ''
                    }`}
                    key={item.title}
                  >
                    <i
                      className={`mk-insight-dot mk-insight-dot--${item.tone}`}
                    />

                    <div>
                      <strong>{item.title}</strong>

                      <small>
                        <b>{item.tag}</b> {item.meta}
                      </small>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div className="mk-insight-detail">

              <div className="mk-detail-meta">
                <span className="mk-risk">
                  Risk
                </span>

                <span>
                  Detected 2 hours ago · Confidence:{' '}
                  <b>High</b>
                </span>
              </div>

              <h3>
                Europe is the primary contributor to the revenue decline.
              </h3>

              <p className="mk-detail-summary">
                Tracefable found an 8.4% revenue decline, with Europe contributing
                the largest share of the movement.
              </p>

              <div className="mk-metrics">
                <div>
                  <strong>↓ 8.4%</strong>
                  <span>Revenue change</span>
                </div>

                <div>
                  <strong>18%</strong>
                  <span>Revenue share</span>
                </div>

                <div>
                  <strong>64%</strong>
                  <span>Contribution to decline</span>
                </div>
              </div>

              <div className="mk-detail-bottom">
                <div>
                  <small>SUPPORTING EVIDENCE</small>

                  <p>
                    fct_revenue · 12,884 rows
                    <br />
                    dim_regions · 4,190 rows
                    <br />
                    7 queries · 2.4s
                  </p>
                </div>

                <button className="mk-small-btn">
                  Ask Tracefable
                </button>
              </div>
            </div>
          </div>
        </section>


        {/* =========================================================
            HOW IT WORKS
        ========================================================= */}

        <section
          id="how"
          className="mk-section mk-how"
        >
          <div className="mk-section-head centered compact">
            <p className="mk-eyebrow">
              How it works
            </p>

            <h2>
              From question to <span>proven answer.</span>
            </h2>

            <p>
              Tracefable plans the investigation, queries the warehouse, and
              turns verified evidence into a decision-ready explanation.
            </p>
          </div>

          <div className="mk-evidence-flow">
            {[
              [
                '01',
                'Ask',
                'Natural-language business question',
              ],
              [
                '02',
                'Plan',
                'Competing hypotheses + evidence purpose',
              ],
              [
                '03',
                'Prove',
                'Warehouse results validate the claims',
              ],
              [
                '04',
                'Decide',
                'Narrative with confidence and unknowns',
              ],
            ].map(([num, title, text], index) => (
              <div
                className="mk-flow-card"
                key={num}
              >
                <span>{num}</span>

                <strong>{title}</strong>

                <p>{text}</p>

                {index < 3 && <Arrow />}
              </div>
            ))}
          </div>
        </section>


        {/* =========================================================
            EVIDENCE / TRUST
        ========================================================= */}

        <section
          id="evidence"
          className="mk-section mk-evidence"
        >
          <div className="mk-evidence-banner">
            <div>
              <p className="mk-eyebrow">
                Built for trust
              </p>

              <h2>
                Proven, not plausible.
              </h2>

              <p>
                Every answer is grounded in governed business context and
                warehouse evidence.
              </p>
            </div>

            <div className="mk-trust-grid">
              <span>
                <b>Governed</b>
                Approved metrics and definitions
              </span>

              <span>
                <b>Warehouse-native</b>
                Queries run where your data lives
              </span>

              <span>
                <b>Evidence-bound</b>
                Claims backed by results
              </span>

              <span>
                <b>Traceable</b>
                Follow answers to their source
              </span>
            </div>
          </div>
        </section>


        {/* =========================================================
            CONNECTORS
        ========================================================= */}

        <section
          id="connectors"
          className="mk-section mk-connectors"
        >
          <div className="mk-section-head split-head connector-head">
            <div>
              <p className="mk-eyebrow">
                Connect your data
              </p>

              <h2>
                Works with the warehouses{' '}
                <span>you already use.</span>
              </h2>
            </div>

            <p>
              Connect to multiple warehouse environments and keep your data
              where it already lives.
            </p>
          </div>

          <div className="mk-connector-grid">
            {connectors.map((connector) => (
              <div
                className="mk-connector"
                key={connector.name}
              >
                <b>{connector.mark}</b>
                <span>{connector.name}</span>
              </div>
            ))}
          </div>
        </section>


        {/* =========================================================
            CTA
        ========================================================= */}

        <section className="mk-cta-section">
          <div className="mk-cta-card">
            <div>
              <p className="mk-eyebrow">
                Business Intelligence Made Easy
              </p>

              <h2>
                Your data has answers.
                <br />
                <span>Tracefable helps you find them.</span>
              </h2>
            </div>

            <div className="mk-cta-actions">
              <button
                className="hp-btn hp-btn--primary"
                onClick={() => openAccess('homepage-cta')}
              >
                Request enterprise access
              </button>

              <a
                className="hp-btn hp-btn--ghost"
                href="#how"
              >
                See how it works <Arrow />
              </a>
            </div>
          </div>
        </section>
      </main>


      {/* =========================================================
          FOOTER
      ========================================================= */}

      <footer className="hp-footer">
        <div className="hp-footer-inner">

          <div>
            <span className="hp-nav-logo hp-nav-logo--sm">
              K
            </span>

            <span className="hp-nav-wordmark hp-nav-wordmark--muted">
              TRACEFABLE
            </span>

            <p>
              Evidence-driven business intelligence for modern teams.
            </p>
          </div>

          <div className="hp-footer-links">
            <a href="#problem">Problem</a>
            <a href="#how">How it works</a>
            <a href="#insights">Insights</a>
            <a href="#evidence">Evidence</a>
            <a href="#connectors">Connectors</a>
          </div>

          <span className="hp-footer-copy">
            © 2026 Tracefable. All rights reserved.
          </span>
        </div>
      </footer>


      <GetAccessModal
        isOpen={accessOpen}
        onClose={() => setAccessOpen(false)}
        sourcePage={accessSource}
      />
    </div>
  );
}

export default HomePage;