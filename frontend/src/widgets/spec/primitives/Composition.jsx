import { formatCell } from '../format.js';
import { OTHER, PARTS } from '../theme.js';
import { Figure } from '../parts.jsx';

/**
 * Parts of a whole: one 100% bar of segments separated by a 2px surface gap, and a legend that lists each part's
 * value and share. Parts keep the pipeline's order and their color by position; past six they fold into Other.
 */
export default function Composition({ view, cols, rows }) {
  const y = view.y[0];
  const items = rows.map((r) => ({ label: formatCell(r[view.x], cols[view.x]), v: Math.max(0, r[y] || 0) }));
  const parts = items.length > PARTS.length
    ? [...items.slice(0, PARTS.length - 1), { label: 'Other', v: items.slice(PARTS.length - 1).reduce((a, b) => a + b.v, 0), other: true }]
    : items;
  const total = parts.reduce((a, b) => a + b.v, 0);
  if (!total) return <div className="empty">Nothing to show</div>;
  const color = (p, i) => (p.other ? OTHER : PARTS[i]);
  return (
    <div className="spec-composition">
      <div className="spec-headline">
        <div className="spec-headline-value"><Figure value={total} col={cols[y]} /></div>
        <div className="spec-headline-meta"><span className="spec-headline-label">Total {(cols[y]?.label || '').toLowerCase()}</span></div>
      </div>
      <div className="composition-bar" role="img" aria-label={parts.map((p) => `${p.label} ${((p.v / total) * 100).toFixed(0)}%`).join(', ')}>
        {parts.filter((p) => p.v > 0).map((p) => (
          <span key={p.label} style={{ flexGrow: p.v, background: color(p, parts.indexOf(p)) }}
            title={`${p.label}: ${formatCell(p.v, cols[y])} (${((p.v / total) * 100).toFixed(1)}%)`} />
        ))}
      </div>
      <div className="composition-legend" role="list">
        {parts.map((p, i) => (
          <div key={p.label} className="composition-item" role="listitem">
            <i style={{ background: color(p, i) }} />
            <span className="composition-label">{p.label}</span>
            <span className="composition-value">{formatCell(p.v, cols[y])}</span>
            <span className="composition-share">{((p.v / total) * 100).toFixed(p.v / total < 0.1 ? 1 : 0)}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}
