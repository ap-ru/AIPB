import { formatCell } from '../format.js';
import { DeltaPill, Figure } from '../parts.jsx';

/**
 * One headline number, a signed change as a pill (colored by polarity: whether up is good) and a secondary figure.
 * With view.x the rows are a short ascending series: the last row is the value, the row before is the comparison
 * (unless a compare column is set) and every row draws the sparkline - muted, with the current period in the accent.
 */
export default function Metric({ view, cols, rows }) {
  const series = Boolean(view.x) && rows.length > 0;
  const r = (series ? rows[rows.length - 1] : rows[0]) || {};
  const prevRow = series && rows.length > 1 ? rows[rows.length - 2] : null;
  const value = r[view.value];
  const compare = view.compare ? r[view.compare] : prevRow ? prevRow[view.value] : null;
  const delta = typeof compare === 'number' && compare !== 0 && typeof value === 'number' ? (value - compare) / Math.abs(compare) : null;
  const vs = view.compare
    ? `vs ${cols[view.compare]?.label} ${formatCell(compare, cols[view.compare])}`
    : prevRow ? `vs ${formatCell(prevRow[view.x], cols[view.x])}` : null;
  return (
    <div className="spec-metric">
      <div className="spec-metric-value"><Figure value={value} col={cols[view.value]} /></div>
      {series && <div className="spec-metric-period">{formatCell(r[view.x], cols[view.x])}</div>}
      {delta !== null && (
        <div className="spec-metric-delta"><DeltaPill delta={delta} polarity={view.polarity} /> <span className="muted">{vs}</span></div>
      )}
      {view.secondary && (
        <div className="spec-metric-secondary">
          {cols[view.secondary]?.label} <b>{formatCell(r[view.secondary], cols[view.secondary])}</b>
        </div>
      )}
      {series && rows.length > 1 && <Sparkline values={rows.map((x) => x[view.value])} />}
    </div>
  );
}

function Sparkline({ values }) {
  const nums = values.map((v) => (typeof v === 'number' ? v : null));
  const present = nums.filter((v) => v !== null);
  if (present.length < 2) return null;
  const min = Math.min(...present);
  const max = Math.max(...present);
  const span = max - min || 1;
  const W = 120, H = 36, pad = 4;
  const pt = (v, i) => [(i / (nums.length - 1)) * W, pad + (1 - (v - min) / span) * (H - pad * 2)];
  const pts = nums.map((v, i) => (v === null ? null : pt(v, i))).filter(Boolean);
  const d = pts.map(([x, y], i) => (i ? 'L' : 'M') + x.toFixed(1) + ' ' + y.toFixed(1)).join(' ');
  const [lx, ly] = pts[pts.length - 1];
  return (
    <div className="sparkline" aria-hidden="true">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" width="100%" height={H}>
        <path d={`${d} L${lx} ${H} L0 ${H} Z`} fill="var(--brand)" opacity="0.08" />
        <path d={d} fill="none" stroke="var(--chart-muted-ink)" strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
      </svg>
      <span className="sparkline-dot" style={{ top: (ly / H) * 100 + '%' }} />
    </div>
  );
}
