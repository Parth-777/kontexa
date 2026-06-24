import { useMemo, useState } from 'react';
import { formatMetricValue } from '../utils/semanticMetricFormatter';
import { sanitizeDisplayText } from '../utils/presentationSanitizer';
import './TableRenderer.css';

function formatCell(value, formatted, format, columnKey) {
  if (formatted != null && formatted !== '') {
    return sanitizeDisplayText(formatted);
  }
  if (value == null) return '—';
  if (typeof value === 'number') {
    return formatMetricValue(value, format || columnKey);
  }
  return sanitizeDisplayText(String(value));
}

/**
 * Enterprise dark table for grouped analytical results.
 */
export default function TableRenderer({ spec }) {
  const [sortKey, setSortKey] = useState(null);
  const [sortDir, setSortDir] = useState('desc');

  const columns = spec?.columns || [];
  const rows = spec?.rows || [];
  const sortable = spec?.sortable !== false;
  const compact = spec?.compact !== false;

  const sortedRows = useMemo(() => {
    if (!sortKey) return rows;
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir;
      return String(av ?? '').localeCompare(String(bv ?? '')) * dir;
    });
  }, [rows, sortKey, sortDir]);

  if (!columns.length || !rows.length) return null;

  const handleSort = (key) => {
    if (!sortable) return;
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir('desc');
    }
  };

  const displayKey = (col) => {
    if (col.key === 'value') return 'value_formatted';
    if (col.key === 'share_pct') return 'share_formatted';
    return col.key;
  };

  return (
    <div className={`tbl-wrap${compact ? ' tbl-wrap--compact' : ''}`}>
      {spec.title && <p className="tbl-title">{spec.title}</p>}
      <div className="tbl-scroll">
        <table className="tbl">
          <thead>
            <tr>
              {columns.map((col) => (
                <th
                  key={col.key}
                  className={sortable ? 'tbl-th--sortable' : ''}
                  onClick={() => handleSort(col.key)}
                >
                  {col.label}
                  {sortable && sortKey === col.key && (
                    <span className="tbl-sort-icon">{sortDir === 'asc' ? '↑' : '↓'}</span>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sortedRows.map((row, i) => (
              <tr key={i}>
                {columns.map((col) => {
                  const rawKey = col.key;
                  const fmtKey = displayKey(col);
                  return (
                    <td key={rawKey} className={`tbl-cell--${col.format || 'text'}`}>
                      {formatCell(row[rawKey], row[fmtKey], col.format, col.key)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
