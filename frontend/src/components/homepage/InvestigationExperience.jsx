import { useState } from 'react';
import useInView from './useInView';

const STAGES = [
  {
    id: 'changed',
    label: 'What changed',
    detail: 'Revenue declined versus prior period.',
    node: 'top',
  },
  {
    id: 'where',
    label: 'Where it came from',
    detail: 'Regional contribution isolates Europe.',
    node: 'left',
  },
  {
    id: 'why',
    label: 'Why it matters',
    detail: '31% compression at 18% share is material.',
    node: 'right',
  },
  {
    id: 'attention',
    label: 'What deserves attention',
    detail: 'Europe ranks as the primary contributor.',
    node: 'bottom',
  },
];

export default function InvestigationExperience() {
  const [ref, inView] = useInView({ threshold: 0.2 });
  const [active, setActive] = useState('where');
  const current = STAGES.find((s) => s.id === active) || STAGES[1];

  return (
    <section id="how" className="hx-exp hx-invest" ref={ref}>
      <div className="hx-invest-head">
        <p className="hx-kicker">The Kontexa investigation</p>
        <h2 className="hx-title-md">From a question to a finding.</h2>
      </div>

      <div className={`hx-invest-board ${inView ? 'is-live' : ''}`}>
        <div className="hx-invest-question">
          <span>›</span>
          Why is revenue declining?
        </div>

        <div className="hx-invest-graph" role="group" aria-label="Investigation stages">
          {STAGES.map((stage) => (
            <button
              key={stage.id}
              type="button"
              className={`hx-node hx-node--${stage.node} ${active === stage.id ? 'is-active' : ''}`}
              onMouseEnter={() => setActive(stage.id)}
              onFocus={() => setActive(stage.id)}
              onClick={() => setActive(stage.id)}
            >
              {stage.label}
            </button>
          ))}

          <div className="hx-invest-center">
            <p className="hx-invest-center-label">Finding</p>
            <p className="hx-invest-center-title">Europe</p>
            <p className="hx-invest-center-metric">Margin compression −31%</p>
            <p className="hx-invest-center-detail">{current.detail}</p>
            <div className="hx-invest-chain" aria-hidden>
              <span>Revenue</span>
              <i>→</i>
              <span>Region</span>
              <i>→</i>
              <span>Europe</span>
              <i>→</i>
              <span>Driver</span>
            </div>
          </div>

          <svg className="hx-invest-lines" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden>
            <line x1="50" y1="18" x2="50" y2="38" />
            <line x1="18" y1="50" x2="38" y2="50" />
            <line x1="82" y1="50" x2="62" y2="50" />
            <line x1="50" y1="82" x2="50" y2="62" />
          </svg>
        </div>
      </div>
    </section>
  );
}
