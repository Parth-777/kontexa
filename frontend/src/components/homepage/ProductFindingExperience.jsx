import { useMemo, useState } from 'react';
import useInView from './useInView';

const SEGMENTS = [
  {
    id: 'na',
    rank: '#1',
    name: 'North America',
    bar: 100,
    value: '$4.2M',
    pct: '68%',
    delta: '+4%',
    tone: 'up',
    finding: 'North America remains the largest base, with modest growth that does not explain the decline.',
    evidence: ['68% revenue share', '+4% period change', 'Largest absolute base'],
  },
  {
    id: 'eu',
    rank: '#2',
    name: 'Europe',
    bar: 26,
    value: '$1.1M',
    pct: '18%',
    delta: '−31%',
    tone: 'down',
    finding: 'Europe is the primary contributor to the decline.',
    evidence: ['31% margin compression', '18% revenue share', '3.8× median gap'],
  },
  {
    id: 'apac',
    rank: '#3',
    name: 'APAC',
    bar: 15,
    value: '$0.6M',
    pct: '10%',
    delta: '+8%',
    tone: 'up',
    finding: 'APAC is growing, but its share is not large enough to offset Europe’s compression.',
    evidence: ['+8% period change', '10% revenue share', 'Secondary growth pocket'],
  },
  {
    id: 'other',
    rank: '—',
    name: 'Other',
    bar: 6,
    value: '$0.2M',
    pct: '4%',
    delta: '−5%',
    tone: 'flat',
    finding: 'Remaining regions are small contributors and do not explain the headline movement.',
    evidence: ['4% revenue share', '−5% period change', 'Low materiality'],
  },
];

export default function ProductFindingExperience() {
  const [ref, inView] = useInView({ threshold: 0.18 });
  const [selectedId, setSelectedId] = useState('eu');
  const [showTrail, setShowTrail] = useState(true);
  const selected = useMemo(
    () => SEGMENTS.find((s) => s.id === selectedId) || SEGMENTS[1],
    [selectedId]
  );

  return (
    <section id="evidence" className="hx-exp hx-product" ref={ref}>
      <div className="hx-product-head">
        <div>
          <p className="hx-kicker">The finding</p>
          <h2 className="hx-title-md">Evidence becomes the product.</h2>
        </div>
        <p className="hx-product-note">Illustrative product example — not live tenant data.</p>
      </div>

      <div className={`hx-product-stage ${inView ? 'is-live' : ''}`}>
        <div className="hx-product-main">
          <div className="hx-product-q">
            <span>›</span>
            Why is revenue declining?
          </div>
          <div className="hx-product-meta">
            <span className="hx-pill">CONTRIBUTION_BREAKDOWN</span>
            <span>Revenue by Region</span>
          </div>

          <div className="hx-bars" role="listbox" aria-label="Regional contribution">
            {SEGMENTS.map((seg) => {
              const active = seg.id === selectedId;
              return (
                <button
                  key={seg.id}
                  type="button"
                  role="option"
                  aria-selected={active}
                  className={`hx-bar-row ${active ? 'is-active' : ''} is-${seg.tone}`}
                  onClick={() => setSelectedId(seg.id)}
                >
                  <span className="hx-bar-rank">{seg.rank}</span>
                  <span className="hx-bar-name">{seg.name}</span>
                  <span className="hx-bar-track">
                    <span className="hx-bar-fill" style={{ width: `${seg.bar}%` }} />
                  </span>
                  <span className="hx-bar-val">{seg.value}</span>
                  <span className="hx-bar-pct">{seg.pct}</span>
                  <span className="hx-bar-delta">{seg.delta}</span>
                </button>
              );
            })}
          </div>
        </div>

        <aside className="hx-finding-panel">
          <p className="hx-finding-kicker">Finding</p>
          <h3 className="hx-finding-title">{selected.finding}</h3>
          <div className="hx-finding-metrics">
            {selected.evidence.map((e) => (
              <span key={e}>{e}</span>
            ))}
          </div>

          <button
            type="button"
            className={`hx-evidence-btn ${showTrail ? 'is-open' : ''}`}
            onClick={() => setShowTrail((v) => !v)}
            aria-expanded={showTrail}
          >
            Evidence
          </button>

          {showTrail ? (
            <div className="hx-evidence-path">
              <span>Dimension → Region</span>
              <span>Metric → Revenue</span>
              <span>Comparison → Previous period</span>
            </div>
          ) : null}
        </aside>
      </div>
    </section>
  );
}
