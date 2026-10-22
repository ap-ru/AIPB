import NumberFlow from '@number-flow/react';
import { flowFormat, formatCell, signedPct } from './format.js';
import { deltaTone } from './theme.js';

/** A figure: animated digits for numeric formats, the plain formatted value otherwise. */
export function Figure({ value, col }) {
  const f = flowFormat(value, col);
  return f ? <NumberFlow value={f.value} format={f.format} locales="en-US" /> : formatCell(value, col);
}

/** A signed % change as a tinted pill; its color comes from the polarity (whether up is good), never the sign alone. */
export function DeltaPill({ delta, polarity, suffix }) {
  if (delta === null || delta === undefined || !Number.isFinite(delta)) return null;
  const tone = deltaTone(delta, polarity);
  return (
    <span className={'delta-pill ' + tone}>
      <span aria-hidden="true">{delta > 0 ? '↑' : delta < 0 ? '↓' : '→'}</span> {signedPct(delta)}
      {suffix && <span className="delta-pill-suffix"> {suffix}</span>}
    </span>
  );
}

/**
 * The figure a chart card leads with, computed from the rows by code (the spec only names which one):
 * latest - the last period, with its change vs the period before; total / average - over every row shown;
 * change - last vs first.
 */
export function headlineOf(view, cols, rows) {
  const kind = view.headline;
  const y = view.y?.[0];
  if (!kind || kind === 'none' || !y || !rows.length) return null;
  const xs = (r) => (r ? formatCell(r[view.x], cols[view.x]) : '');
  const vals = rows.map((r) => r[y]).filter((v) => typeof v === 'number');
  if (!vals.length) return null;
  const first = rows[0][y];
  const last = rows[rows.length - 1][y];
  const prev = rows.length > 1 ? rows[rows.length - 2][y] : null;
  const pct = (a, b) => (typeof a === 'number' && typeof b === 'number' && b !== 0 ? (a - b) / Math.abs(b) : null);
  const range = rows.length > 1 ? `${xs(rows[0])} – ${xs(rows[rows.length - 1])}` : xs(rows[0]);
  switch (kind) {
    case 'latest':
      return { label: xs(rows[rows.length - 1]), value: last, delta: pct(last, prev), vs: prev !== null ? 'vs ' + xs(rows[rows.length - 2]) : null };
    case 'total':
      return { label: 'Total · ' + range, value: vals.reduce((a, b) => a + b, 0) };
    case 'average':
      return { label: 'Average · ' + range, value: vals.reduce((a, b) => a + b, 0) / vals.length };
    case 'change':
      return { label: 'Change since ' + xs(rows[0]), value: last, delta: pct(last, first), vs: null };
    default:
      return null;
  }
}

export function Headline({ view, cols, rows }) {
  const h = headlineOf(view, cols, rows);
  if (!h) return null;
  return (
    <div className="spec-headline">
      <div className="spec-headline-value"><Figure value={h.value} col={cols[view.y[0]]} /></div>
      <div className="spec-headline-meta">
        <DeltaPill delta={h.delta} polarity={view.polarity} suffix={h.vs} />
        <span className="spec-headline-label">{h.label}</span>
      </div>
    </div>
  );
}

/** Shared tooltip: the category or period, then one row per series - value strong, name secondary, a line key.
 * colorOf maps a series' column name to its color (a mark's fill can be the muted step, so it isn't used). */
export function ChartTooltip({ active, payload, label, xCol, cols, colorOf, line }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="chart-tip">
      <div className="chart-tip-label">{formatCell(label, xCol)}</div>
      {payload.map((p) => (
        <div key={p.dataKey} className="chart-tip-row">
          <span className={'chart-tip-key' + (line ? ' line' : '')} style={{ background: colorOf?.[p.dataKey] ?? p.color }} />
          <b>{formatCell(p.value, cols[p.dataKey])}</b>
          <span className="chart-tip-name">{cols[p.dataKey]?.label || p.dataKey}</span>
        </div>
      ))}
    </div>
  );
}

/** Legend for 2+ series: a swatch that mirrors the mark (rect for bars, line for lines), the label in text ink. */
export function SeriesLegend({ names, cols, colors, line }) {
  if (names.length < 2) return null;
  return (
    <div className="series-legend">
      {names.map((n, i) => (
        <span key={n}><i className={line ? 'line' : ''} style={{ background: colors[i] }} />{cols[n]?.label || n}</span>
      ))}
    </div>
  );
}
