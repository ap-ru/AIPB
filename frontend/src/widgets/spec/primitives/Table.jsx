import { formatCell, formatOf, isNumeric } from '../format.js';
import { TONE_CLASS } from '../theme.js';

export function Cell({ value, col, row }) {
  if (formatOf(col) === 'badge' && value !== null && value !== undefined) {
    return <span className={'tag ' + (TONE_CLASS[col.tones?.[value]] ?? '')}>{value}</span>;
  }
  return formatCell(value, col, row);
}

/** Rows of records; numbers right-aligned, status columns as labelled badges. */
export default function Table({ view, cols, rows }) {
  const names = view.columns || Object.values(cols).filter((c) => !c.hidden).map((c) => c.name);
  if (!rows.length) return <div className="empty">No rows</div>;
  return (
    <div className="spec-table-wrap">
      <table className="table spec-table">
        <thead><tr>{names.map((n) => <th key={n} className={isNumeric(cols[n]) ? 'num' : undefined}>{cols[n]?.label || n}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{names.map((n) => (
              <td key={n} className={isNumeric(cols[n]) ? 'num' : undefined}><Cell value={r[n]} col={cols[n]} row={r} /></td>
            ))}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
