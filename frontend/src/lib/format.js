export function money(v, digits) {
  if (v === undefined || v === null || isNaN(v)) return '-';
  const a = Math.abs(v);
  const s = v < 0 ? '-' : '';
  if (a >= 1e9) return s + '$' + (a / 1e9).toFixed(digits ?? 2) + 'B';
  if (a >= 1e6) return s + '$' + (a / 1e6).toFixed(digits ?? 1) + 'M';
  if (a >= 1e3) return s + '$' + (a / 1e3).toFixed(digits ?? 0) + 'K';
  return s + '$' + a.toFixed(0);
}

export function pct(v, digits = 1) {
  if (v === undefined || v === null || isNaN(v)) return '-';
  return (v * 100).toFixed(digits) + '%';
}

export function num(v) {
  if (v === undefined || v === null || isNaN(v)) return '-';
  return Math.round(v).toLocaleString('en-US');
}

export function formatValue(v, format) {
  switch (format) {
    case 'money': return money(v);
    case 'pct': return pct(v);
    case 'ratio': return (v || 0).toFixed(2) + 'x';
    default: return num(v);
  }
}

export function timeAgo(iso) {
  if (!iso) return '';
  const d = (Date.now() - new Date(iso).getTime()) / 1000;
  if (d < 60) return Math.max(1, Math.round(d)) + 's ago';
  if (d < 3600) return Math.round(d / 60) + 'm ago';
  if (d < 86400) return Math.round(d / 3600) + 'h ago';
  return Math.round(d / 86400) + 'd ago';
}

export function shortDate(iso) {
  if (!iso) return '-';
  return new Date(iso + (iso.length === 10 ? 'T00:00:00' : '')).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

export function healthColor(h) {
  if (h >= 75) return 'var(--green)';
  if (h >= 50) return 'var(--amber)';
  return 'var(--red)';
}

export const LOB_COLORS = {
  'Corporate Banking': '#008555',
  'Commercial Lending': '#1b5e8c',
  'Capital Markets': '#d93d00',
  'Global Markets': '#8c6d1f',
};
export const CATEGORY_TAG = { Risk: 'red', Opportunity: 'green', Service: 'amber', Market: 'blue' };
