import useInView from './useInView';

const POINTS = [
  { x: 8, y: 18, label: 'ARR' },
  { x: 22, y: 12, label: 'CAC' },
  { x: 38, y: 22, label: 'NA' },
  { x: 12, y: 42, label: 'QoQ' },
  { x: 48, y: 14, label: 'APAC' },
  { x: 62, y: 28, label: 'Margin' },
  { x: 78, y: 16, label: 'Churn' },
  { x: 88, y: 34, label: 'NPS' },
  { x: 18, y: 68, label: 'Cohort' },
  { x: 34, y: 58, label: 'Pipeline' },
  { x: 52, y: 72, label: 'Segment' },
  { x: 70, y: 62, label: 'Channel' },
  { x: 84, y: 74, label: 'Export' },
  { x: 28, y: 34, label: 'KPI' },
  { x: 58, y: 46, label: 'Report' },
  { x: 74, y: 48, label: 'Alert' },
];

export default function HeroCanvas() {
  const [ref, inView] = useInView({ threshold: 0.35 });

  return (
    <div className={`hx-canvas ${inView ? 'is-live' : ''}`} ref={ref} aria-hidden>
      <div className="hx-canvas-label">DATA → SIGNALS → FINDING</div>
      <div className="hx-field">
        {POINTS.map((p) => (
          <span
            key={`${p.label}-${p.x}`}
            className="hx-point"
            style={{ left: `${p.x}%`, top: `${p.y}%` }}
          >
            {p.label}
          </span>
        ))}
        <svg className="hx-rays" viewBox="0 0 100 100" preserveAspectRatio="none">
          <path d="M20 30 L58 52" />
          <path d="M48 18 L58 52" />
          <path d="M78 28 L58 52" />
          <path d="M30 68 L58 52" />
          <path d="M72 70 L58 52" />
        </svg>
        <div className="hx-signal">
          <span className="hx-signal-rank">01</span>
          <span className="hx-signal-name">Europe</span>
          <span className="hx-signal-metric">Margin compression</span>
          <span className="hx-signal-delta">−31%</span>
          <span className="hx-signal-tag">Primary contributor</span>
        </div>
      </div>
    </div>
  );
}
