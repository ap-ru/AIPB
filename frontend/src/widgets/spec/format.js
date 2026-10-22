import { money } from '../../lib/format.js';

/**
 * Formats a value by its column, as the backend's primitives catalog describes. A date column with a grain shows its
 * period (Sep 2026, 2026 Q3, 2026). A column with unitFrom takes its unit per row from another column (pass the row):
 * a covenant's actual is 3.25x on one row and 70% on the next.
 */
export function formatCell(v, col, row) {
  if (v === null || v === undefined || (typeof v === 'number' && Number.isNaN(v))) return '—';
  const f = formatOf(col, row);
  if (f === 'date' && col?.grain) return period(v, col.grain);
  switch (f) {
    case 'currency': return money(v);
    case 'percent': return (v * 100).toFixed(Math.abs(v) < 0.1 ? 1 : 0) + '%';
    case 'percentPoints': return v.toFixed(Number.isInteger(v) ? 0 : 1) + '%';
    case 'integer': return Math.round(v).toLocaleString('en-US');
    case 'number': return v.toLocaleString('en-US', { maximumFractionDigits: 2 });
    case 'multiple': return v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + 'x';
    case 'days': { const d = Math.round(v); return d.toLocaleString('en-US') + (Math.abs(d) === 1 ? ' day' : ' days'); }
    case 'boolean': return v ? 'Yes' : 'No';
    case 'date': return new Date(v).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' });
    default: return String(v);
  }
}

/** The period a grain date stands for: Sep 2026, 2026 Q3, 2026. */
function period(v, grain) {
  const d = new Date(v);
  if (grain === 'year') return String(d.getUTCFullYear());
  if (grain === 'quarter') return `${d.getUTCFullYear()} Q${Math.floor(d.getUTCMonth() / 3) + 1}`;
  return d.toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

/** The default format of each unit (matches the backend's ResultSet.defaultFormat). */
const UNIT_FORMAT = { USD: 'currency', ratio: 'percent', percent: 'percentPoints', count: 'integer', date: 'date', multiple: 'multiple', days: 'days' };

/** Short axis-tick form: compact currency, month + year for dates. */
export function formatTick(v, col) {
  if (formatOf(col) === 'date' && col?.grain && col.grain !== 'month') return period(v, col.grain);
  if (formatOf(col) === 'date') return new Date(v).toLocaleDateString('en-US', { month: 'short', year: '2-digit', timeZone: 'UTC' });
  if (formatOf(col) === 'currency') return trimZeros(money(v));
  return formatCell(v, col);
}

/** $1.50M -> $1.5M, $3.0M -> $3M: ticks stay exact (never rounded to a misleading whole number) without noise. */
const trimZeros = (s) => s.replace(/(\.\d*?)0+([KMB])$/, '$1$2').replace(/\.([KMB])$/, '$1');

export function formatOf(col, row) {
  if (!col) return 'text';
  if (col.unitFrom && row) {
    const unit = col.unitFrom.map?.[row[col.unitFrom.column]];
    if (unit && UNIT_FORMAT[unit]) return UNIT_FORMAT[unit];
  }
  if (col.format) return col.format;
  return col.type === 'number' ? 'number' : col.type === 'date' ? 'date' : 'text';
}

export const isNumeric = (col) => col && col.type === 'number';

/** Columns by name. */
export const byName = (columns) => Object.fromEntries((columns || []).map((c) => [c.name, c]));

/** Columns meant to be shown (not carried for another column, like a covenant's unit or a client id beside its name). */
export const visible = (columns) => (columns || []).filter((c) => !c.hidden);

/** NumberFlow (Intl.NumberFormat) options for a numeric column, matching formatCell; null when it isn't numeric. */
export function flowFormat(v, col) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return null;
  switch (formatOf(col)) {
    case 'currency': return { value: v, format: { style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: Math.abs(v) >= 1e9 ? 2 : 1 } };
    case 'percent': return { value: v, format: { style: 'percent', maximumFractionDigits: Math.abs(v) < 0.1 ? 1 : 0 } };
    case 'percentPoints': return { value: v / 100, format: { style: 'percent', maximumFractionDigits: Number.isInteger(v) ? 0 : 1 } };
    case 'integer': return { value: Math.round(v), format: { maximumFractionDigits: 0 } };
    case 'number': return { value: v, format: { maximumFractionDigits: 2 } };
    default: return null;
  }
}

/** Signed percent change, e.g. +4.2% / −3.0%. */
export const signedPct = (d) => (d > 0 ? '+' : d < 0 ? '−' : '') + Math.abs(d * 100).toFixed(1) + '%';
