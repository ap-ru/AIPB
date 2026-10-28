import { useState } from 'react';
import Metric from './primitives/Metric.jsx';
import Bar from './primitives/Bar.jsx';
import Line from './primitives/Line.jsx';
import Table from './primitives/Table.jsx';
import List from './primitives/List.jsx';
import Meter from './primitives/Meter.jsx';
import Composition from './primitives/Composition.jsx';
import Narrative from './primitives/Narrative.jsx';
import { byName, formatCell, visible } from './format.js';
import { MAX_SERIES } from './theme.js';

const PRIMITIVES = { metric: Metric, bar: Bar, line: Line, table: Table, list: List, meter: Meter, composition: Composition, narrative: Narrative };
/** Primitives that draw a picture of several rows, so they offer the same rows as a table. */
const CHARTS = new Set(['bar', 'line', 'composition']);

/**
 * Renders rows with one design-system primitive, as a spec's view says. Shared by placed widgets and the workbench
 * preview, so a preview looks exactly like the widget will. Charts can switch to a table of the same rows. An insight
 * (the narrative primitive) shows the sentences the backend rendered from the rows ({@code narrative}).
 */
export default function SpecView({ view, columns, rows, truncated, total, narrative }) {
  const [asTable, setAsTable] = useState(false);
  const chart = CHARTS.has(view.primitive) || (view.primitive === 'meter' && Boolean(view.x));
  const P = asTable ? Table : PRIMITIVES[view.primitive];
  if (!P) return <div className="empty">Unknown primitive “{view.primitive}”</div>;
  const cols = byName(columns);
  const drawn = asTable ? { view: { primitive: 'table', columns: visible(columns).map((c) => c.name) }, cols, rows: rows || [] }
    : pivot(view, cols, rows || []);
  return (
    <div className="spec-view">
      {(chart || truncated) && (
        <div className="spec-view-bar">
          {truncated && <span className="tag amber">Showing {rows.length} of {total ?? 'more'} rows</span>}
          {chart && (
            <button className="view-toggle" onClick={() => setAsTable((t) => !t)} aria-pressed={asTable}>
              {asTable ? 'Chart' : 'Table'}
            </button>
          )}
        </div>
      )}
      <P view={drawn.view} cols={drawn.cols} rows={drawn.rows} narrative={narrative} />
    </div>
  );
}

/**
 * A series role (long-format rows: one per period and series) drawn as one column per series, which is how the
 * charts take several series. Series are ordered by their total, largest first, so the leading series keeps the brand
 * color and carries any emphasis or headline; at most MAX_SERIES are drawn.
 */
export function pivot(view, cols, rows) {
  if (!view.series || !view.y?.length) return { view, cols, rows };
  const y = view.y[0];
  const totals = new Map();
  rows.forEach((r) => { const s = r[view.series]; if (s != null) totals.set(s, (totals.get(s) || 0) + (typeof r[y] === 'number' ? r[y] : 0)); });
  const names = [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([s]) => s).slice(0, MAX_SERIES);
  const key = (s) => `${y}__${s}`;
  const byX = new Map();
  rows.forEach((r) => {
    if (!names.includes(r[view.series])) return;
    if (!byX.has(r[view.x])) byX.set(r[view.x], { [view.x]: r[view.x] });
    byX.get(r[view.x])[key(r[view.series])] = r[y];
  });
  const seriesCols = Object.fromEntries(names.map((s) => [key(s), { ...cols[y], name: key(s), label: formatCell(s, cols[view.series]) }]));
  const { series, ...rest } = view;
  return { view: { ...rest, y: names.map(key) }, cols: { ...cols, ...seriesCols }, rows: [...byX.values()] };
}
