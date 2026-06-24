import {
  asCurrency,
  compactNumber,
  detectFormat,
  formatMetricValue,
  formatPercent,
  resolveChartValueFormat,
} from './semanticMetricFormatter';

describe('semanticMetricFormatter', () => {
  test('formats large currency values compactly', () => {
    expect(asCurrency(42_000_000_000)).toBe('$42.0B');
    expect(asCurrency(1_560_000_000)).toBe('$1.56B');
    expect(asCurrency(24_000_000)).toBe('$24.0M');
    expect(asCurrency(4_500)).toBe('$4.5K');
  });

  test('formats percentages', () => {
    expect(formatPercent(0.234)).toBe('23.4%');
  });

  test('formats plain integers', () => {
    expect(compactNumber(123)).toBe('123');
  });

  test('never uses scientific notation', () => {
    const formatted = asCurrency(4.2e10);
    expect(formatted).not.toMatch(/e/i);
    expect(formatted).toBe('$42.0B');
  });

  test('detects currency columns', () => {
    expect(detectFormat('total_revenue')).toBe('currency');
    expect(detectFormat('operation_cost')).toBe('currency');
  });

  test('resolveChartValueFormat prefers explicit format', () => {
    expect(resolveChartValueFormat({ valueFormat: 'percent', valueKey: 'total_revenue' }))
      .toBe('percent');
    expect(resolveChartValueFormat({ valueKey: 'total_revenue' })).toBe('currency');
  });

  test('formatMetricValue uses format type', () => {
    expect(formatMetricValue(42_800_000_000, 'currency')).toBe('$42.8B');
    expect(formatMetricValue(42_800_000_000, 'total_revenue')).toBe('$42.8B');
  });

  test('formatMetricValue never returns NaN for invalid numbers', () => {
    expect(formatMetricValue(NaN, 'currency')).toBe('—');
    expect(formatMetricValue(Infinity, 'percent')).toBe('—');
    expect(formatMetricValue(NaN, 'currency', { tooltip: true })).toBe('Not available');
  });
});
