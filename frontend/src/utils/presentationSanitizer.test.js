import {
  DASH,
  NOT_AVAILABLE,
  isUnavailable,
  sanitizeDisplayText,
} from './presentationSanitizer';

describe('presentationSanitizer', () => {
  test('detects unavailable numeric values', () => {
    expect(isUnavailable(NaN)).toBe(true);
    expect(isUnavailable(Infinity)).toBe(true);
    expect(isUnavailable(-Infinity)).toBe(true);
    expect(isUnavailable(null)).toBe(true);
    expect(isUnavailable(undefined)).toBe(true);
    expect(isUnavailable(0)).toBe(false);
    expect(isUnavailable(42)).toBe(false);
  });

  test('sanitizes invalid display tokens for cards', () => {
    expect(sanitizeDisplayText('NaN')).toBe(DASH);
    expect(sanitizeDisplayText('NaN%')).toBe(DASH);
    expect(sanitizeDisplayText('Infinity')).toBe(DASH);
    expect(sanitizeDisplayText('-Infinity')).toBe(DASH);
    expect(sanitizeDisplayText('undefined')).toBe(DASH);
    expect(sanitizeDisplayText('42.5%')).toBe('42.5%');
  });

  test('sanitizes invalid display tokens for tooltips', () => {
    expect(sanitizeDisplayText('NaN', { tooltip: true })).toBe(NOT_AVAILABLE);
    expect(sanitizeDisplayText(null, { tooltip: true })).toBe(NOT_AVAILABLE);
  });
});
