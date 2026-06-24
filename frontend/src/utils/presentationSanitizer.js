/**
 * Central guard for invalid numeric values in executive presentation output.
 * Mirrors backend PresentationValueSanitizer.
 */

export const DASH = '—';
export const NOT_AVAILABLE = 'Not available';

const INVALID_TOKEN = /\b(nan|-?infinity|undefined)\b|nan%/i;

export function isUnavailable(value) {
  if (value == null || value === '') return true;
  const n = Number(value);
  return !Number.isFinite(n);
}

/** KPI cards and table cells. */
export function sanitizeDisplayText(text, { tooltip = false } = {}) {
  if (text == null || String(text).trim() === '') {
    return tooltip ? NOT_AVAILABLE : DASH;
  }
  const trimmed = String(text).trim();
  if (INVALID_TOKEN.test(trimmed)) {
    return tooltip ? NOT_AVAILABLE : DASH;
  }
  return String(text);
}
