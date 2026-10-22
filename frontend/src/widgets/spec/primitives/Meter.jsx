import { formatCell } from '../format.js';
import { Figure } from '../parts.jsx';

/**
 * A value against its maximum - utilization of a limit, share of wallet, headroom. The max is the reference column,
 * or 1 for a 0-1 ratio. warnAt (a 0-1 fraction of the max) with polarity sets the fill's state: lowerIsBetter warns at
 * or above it (and is critical past the max), higherIsBetter warns at or below it. The track is a lighter step of the
 * fill's own ramp. With view.x, one labelled meter per row.
 */
export default function Meter({ view, cols, rows }) {
  if (view.x) {
    return (
      <div className="meter-list" role="list">
        {rows.map((r, i) => (
          <div key={i} className="meter-row" role="listitem">
            <div className="between"><span className="meter-row-label">{formatCell(r[view.x], cols[view.x])}</span>
              <span className="meter-row-value"><Figure value={r[view.value]} col={cols[view.value]} />{ofText(view, cols, r)}</span></div>
            <Track view={view} cols={cols} r={r} />
          </div>
        ))}
      </div>
    );
  }
  const r = rows[0] || {};
  return (
    <div className="spec-meter">
      <div className="spec-metric-value"><Figure value={r[view.value]} col={cols[view.value]} /></div>
      <div className="spec-metric-period">{ofText(view, cols, r) || cols[view.value]?.label}</div>
      <Track view={view} cols={cols} r={r} />
      {view.secondary && (
        <div className="spec-metric-secondary">{cols[view.secondary]?.label} <b>{formatCell(r[view.secondary], cols[view.secondary])}</b></div>
      )}
    </div>
  );
}

const ofText = (view, cols, r) => (view.reference && r[view.reference] != null
  ? ` of ${formatCell(r[view.reference], cols[view.reference])} ${(cols[view.reference]?.label || '').toLowerCase()}` : '');

function stateOf(f, view) {
  if (view.warnAt == null || !Number.isFinite(f)) return 'ok';
  if (view.polarity === 'higherIsBetter') return f <= view.warnAt ? 'warn' : 'ok';
  return f > 1 ? 'bad' : f >= view.warnAt ? 'warn' : 'ok';
}

function Track({ view, cols, r }) {
  const v = r[view.value];
  const max = view.reference ? r[view.reference] : 1;
  const f = typeof v === 'number' && max > 0 ? v / max : NaN;
  const state = stateOf(f, view);
  const pct = Number.isFinite(f) ? Math.max(0, Math.min(1, f)) * 100 : 0;
  const label = cols[view.value]?.label || view.value;
  return (
    <div className={'meter ' + state} role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(f * 100) || 0}
      title={Number.isFinite(f) ? `${label}: ${(f * 100).toFixed(0)}% of max` : label}>
      <span className="meter-fill" style={{ width: pct + '%' }} />
      {view.warnAt != null && <span className="meter-mark" style={{ left: view.warnAt * 100 + '%' }} />}
      {state !== 'ok' && <span className="sr-only">{state === 'bad' ? 'Over the limit' : 'At warning level'}</span>}
    </div>
  );
}
