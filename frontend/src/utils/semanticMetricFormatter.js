/**
 * Shared business number formatter — mirrors backend SemanticMetricFormatter
 * and presentation-layer detectFormat rules.
 */

import { DASH, NOT_AVAILABLE } from './presentationSanitizer';

const KNOWN_FORMATS = new Set(['currency', 'percent', 'number', 'text', 'date']);

export function detectFormat(columnKey) {
  if (!columnKey || typeof columnKey !== 'string') return 'number';
  const key = columnKey.toLowerCase();

  if (key.includes('date') || key.includes('time') || key.includes('timestamp')
      || key.endsWith('_day') || key.endsWith('_month') || key.endsWith('_year')) {
    return 'date';
  }
  if (key.includes('revenue') || key.includes('amount') || key.includes('fare')
      || key.includes('cost') || key.includes('price') || key.includes('fee')
      || key.includes('tip') || key.includes('total') || key.includes('sales')) {
    return 'currency';
  }
  if (key.includes('pct') || key.includes('percent') || key.includes('share')
      || key.includes('rate') || key.includes('_ratio') || key.endsWith('ratio')) {
    return 'percent';
  }
  if (key === 'count' || key.endsWith('_count') || key.startsWith('count_')
      || key.includes('id') || key.includes('rank')
      || key.includes('coefficient') || key.includes('distance')
      || key.includes('hours') || key.includes('quantity')) {
    return 'number';
  }
  return 'text';
}

export function asCurrency(amount) {
  const n = Number(amount);
  if (!Number.isFinite(n)) return '—';
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= 1_000_000_000) {
    const b = abs / 1_000_000_000;
    return `${sign}$${b >= 10 ? b.toFixed(1) : b.toFixed(2)}B`;
  }
  if (abs >= 1_000_000) {
    const m = abs / 1_000_000;
    return `${sign}$${m >= 100 ? m.toFixed(0) : m.toFixed(1)}M`;
  }
  if (abs >= 1_000) {
    return `${sign}$${(abs / 1_000).toFixed(1)}K`;
  }
  if (abs >= 100) {
    return `${sign}$${abs.toFixed(0)}`;
  }
  return `${sign}$${abs.toFixed(2)}`;
}

export function asSharePct(pct) {
  const n = Number(pct);
  if (!Number.isFinite(n)) return '—';
  return Math.abs(n) >= 100 ? `${n.toFixed(0)}%` : `${n.toFixed(1)}%`;
}

export function formatPercent(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  const pct = n > 0 && n <= 1 ? n * 100 : n;
  return asSharePct(pct);
}

export function compactNumber(n) {
  const value = Number(n);
  if (!Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  if (abs >= 1_000_000_000) return `${sign}${(abs / 1_000_000_000).toFixed(1)}B`;
  if (abs >= 1_000_000) return `${sign}${(abs / 1_000_000).toFixed(0)}M`;
  if (abs >= 1_000) return `${sign}${(abs / 1_000).toFixed(1)}K`;
  if (abs >= 100 || abs === Math.round(abs)) return `${sign}${abs.toFixed(0)}`;
  return `${sign}${abs.toFixed(1)}`;
}

/**
 * @param {number} value raw numeric value
 * @param {string} formatOrColumn explicit format or column key for detection
 * @param {{ tooltip?: boolean }} [options]
 */
export function formatMetricValue(value, formatOrColumn, { tooltip = false } = {}) {
  const n = Number(value);
  if (!Number.isFinite(n)) {
    return tooltip ? NOT_AVAILABLE : DASH;
  }

  const format = KNOWN_FORMATS.has(formatOrColumn)
    ? formatOrColumn
    : detectFormat(formatOrColumn);

  switch (format) {
    case 'currency':
      return asCurrency(n);
    case 'percent':
      return formatPercent(n);
    case 'number':
      return compactNumber(n);
    case 'text':
    case 'date':
    default:
      if (Number.isInteger(n) || Math.abs(n) >= 100) {
        return compactNumber(n);
      }
      return String(n);
  }
}

/** Resolve chart value format from spec metadata. */
export function resolveChartValueFormat(spec) {
  if (spec?.valueFormat && KNOWN_FORMATS.has(spec.valueFormat)) {
    return spec.valueFormat;
  }
  const columnKey = spec?.valueKey || spec?.yKey || '';
  const detected = detectFormat(columnKey);
  return detected === 'text' || detected === 'date' ? 'number' : detected;
}
