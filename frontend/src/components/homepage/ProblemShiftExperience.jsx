import useInView from './useInView';

const NOISE = [
  'Dashboard', 'Report', 'KPI', 'Table', 'Sheet', 'Funnel', 'Cohort',
  'Revenue', 'Margin', 'Churn', 'Region', 'Product', 'Channel', 'Segment',
  'Filter', 'Export', 'Alert', 'Pipeline', 'ARR', 'CAC', 'NPS', 'QoQ',
  'WoW', 'APAC', 'EMEA', 'LATAM', 'Enterprise', 'SMB', 'Retention', 'Conversion',
];

const TRAIL = ['Question', 'Dashboard', 'Filter', 'Compare', 'Export', 'Analyst', 'Investigate', 'Decide'];

export default function ProblemShiftExperience() {
  const [ref, inView] = useInView({ threshold: 0.2 });

  return (
    <section id="problem" className="hx-exp hx-problem" ref={ref}>
      <div className="hx-problem-top">
        <p className="hx-kicker">The problem</p>
        <h2 className="hx-title-md">
          Your company already has more data than ever.
        </h2>
        <p className="hx-lede">
          More data does not automatically mean more understanding.
        </p>
      </div>

      <div className={`hx-overload ${inView ? 'is-resolved' : ''}`}>
        <div className="hx-overload-field" aria-hidden>
          {NOISE.map((n, i) => (
            <span key={`${n}-${i}`} className="hx-overload-chip" style={{ '--i': i }}>
              {n}
            </span>
          ))}
        </div>
        <div className="hx-overload-focus">
          <div className="hx-focus-item">
            <span>01</span>
            <strong>Europe margin compression</strong>
            <em>−31%</em>
          </div>
          <div className="hx-focus-item">
            <span>02</span>
            <strong>Enterprise concentration</strong>
            <em>High</em>
          </div>
          <div className="hx-focus-item">
            <span>03</span>
            <strong>APAC growth contribution</strong>
            <em>+8%</em>
          </div>
        </div>
      </div>

      <div className="hx-problem-bottom">
        <div className="hx-problem-copy">
          <h3 className="hx-title-sm">
            Dashboards monitor.<br />
            People still investigate.
          </h3>
          <p className="hx-body">
            Monitoring is necessary. Investigation is still a long trail of
            filters, exports, and interpretation.
          </p>
        </div>
        <div className={`hx-trail ${inView ? 'is-on' : ''}`} aria-label="Traditional investigation trail">
          {TRAIL.map((step, i) => (
            <div key={step} className="hx-trail-step" style={{ transitionDelay: `${i * 40}ms` }}>
              <span>{step}</span>
              {i < TRAIL.length - 1 ? <i aria-hidden>→</i> : null}
            </div>
          ))}
        </div>
      </div>

      <div className={`hx-shiftline ${inView ? 'is-on' : ''}`}>
        <span>From monitoring</span>
        <span className="hx-shiftline-arrow" aria-hidden>→</span>
        <span className="hx-shiftline-end">to understanding</span>
      </div>
    </section>
  );
}
