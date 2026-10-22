import { Cell } from './Table.jsx';
import { formatCell } from '../format.js';
import { TONE_COLOR } from '../theme.js';

/** A compact feed: a tone dot (from the badge's tone), title, subtitle, a labelled badge and a meta value on the right. */
export default function List({ view, cols, rows }) {
  if (!rows.length) return <div className="empty">Nothing to show</div>;
  const badgeCol = view.badge ? cols[view.badge] : null;
  return (
    <div className="spec-list" role="list">
      {rows.map((r, i) => {
        const tone = badgeCol?.tones?.[r[view.badge]];
        return (
          <div key={i} className="spec-list-item" role="listitem">
            <span className="spec-list-dot" style={{ background: TONE_COLOR[tone] ?? 'var(--line-2)' }} aria-hidden="true" />
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="spec-list-title">{formatCell(r[view.title], cols[view.title], r)}</div>
              {view.subtitle && <div className="spec-list-subtitle">{formatCell(r[view.subtitle], cols[view.subtitle], r)}</div>}
            </div>
            <div style={{ textAlign: 'right', flexShrink: 0 }}>
              {view.badge && <Cell value={r[view.badge]} col={cols[view.badge]} row={r} />}
              {view.meta && <div className="spec-list-meta">{formatCell(r[view.meta], cols[view.meta], r)}</div>}
            </div>
          </div>
        );
      })}
    </div>
  );
}
