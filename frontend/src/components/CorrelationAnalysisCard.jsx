function formatCoefficient(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return n.toFixed(3);
}

function formatSampleSize(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return new Intl.NumberFormat().format(n);
}

function capitalize(text) {
  if (!text) return '';
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Correlation-specific analysis view — not a segment comparison.
 */
export default function CorrelationAnalysisCard({ analysis, chartData }) {
  const payload = analysis || {};
  const row = chartData?.raw || chartData || {};

  const title = payload.title
    || (payload.source_variable && payload.target_variable
      ? `${payload.source_variable} vs ${payload.target_variable}`
      : 'Correlation analysis');

  const summary = payload.summary || '';
  const coefficient = payload.correlation_coefficient ?? row.coefficient;
  const sampleSize = payload.sample_size ?? row.sample_size;
  const strength = capitalize(payload.strength || row.strength || 'Unknown');
  const interpretation = payload.business_interpretation
    || payload.interpretation
    || row.interpretation
    || '';

  return (
    <div className="correlation-analysis">
      <div className="correlation-analysis__hero">
        <div className="correlation-analysis__coeff-block">
          <span className="correlation-analysis__coeff-label">Correlation coefficient</span>
          <span className="correlation-analysis__coeff-value">{formatCoefficient(coefficient)}</span>
        </div>
        <div className="correlation-analysis__meta">
          <div className="correlation-analysis__meta-item">
            <span className="correlation-analysis__meta-label">Strength</span>
            <span className="correlation-analysis__meta-value">{strength}</span>
          </div>
          <div className="correlation-analysis__meta-item">
            <span className="correlation-analysis__meta-label">Sample size</span>
            <span className="correlation-analysis__meta-value">{formatSampleSize(sampleSize)}</span>
          </div>
        </div>
      </div>

      {summary ? (
        <p className="correlation-analysis__summary">{summary}</p>
      ) : null}

      {interpretation ? (
        <div className="correlation-analysis__interpretation">
          <p className="correlation-analysis__section-label">Business interpretation</p>
          <p className="correlation-analysis__interpretation-text">{interpretation}</p>
        </div>
      ) : null}
    </div>
  );
}
