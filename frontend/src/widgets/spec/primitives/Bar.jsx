import { Bar as RBar, CartesianGrid, Cell, ComposedChart, LabelList, Line as RLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { formatCell, formatTick } from '../format.js';
import { ANIMATE, AXIS_TEXT, GRID, INK, MUTED, SERIES, SURFACE, TICK, emphasisIndex } from '../theme.js';
import { ChartTooltip, Figure, Headline, SeriesLegend } from '../parts.jsx';

/**
 * Magnitude across categories. Rows keep the pipeline's order. With an emphasis intent the emphasized bar keeps the
 * brand color, carries its value, and the rest recede to the muted step. One horizontal series is drawn as a ranked
 * bar list (label | bar | value); a reference column (limit, target) is a dashed ink mark.
 */
export default function Bar({ view, cols, rows }) {
  const ys = view.y.slice(0, SERIES.length);
  const horizontal = view.orientation === 'horizontal';
  if (horizontal && ys.length === 1 && rows.every((r) => !(r[ys[0]] < 0))) return <BarList view={view} cols={cols} rows={rows} />;

  const valueCol = cols[ys[0]];
  const single = ys.length === 1;
  const hi = single ? emphasisIndex(view.emphasis, rows, ys[0]) : -1;
  const colorOf = Object.fromEntries([...ys.map((y, i) => [y, SERIES[i]]), [view.reference, AXIS_TEXT]]);
  const height = horizontal ? Math.max(140, rows.length * 32 + 40) : 230;
  const categoryAxis = horizontal
    ? <YAxis type="category" dataKey={view.x} tick={TICK} axisLine={false} tickLine={false} width={130}
        tickFormatter={(v) => formatTick(v, cols[view.x])} />
    : <XAxis dataKey={view.x} tick={TICK} axisLine={{ stroke: GRID }} tickLine={false} tickFormatter={(v) => formatTick(v, cols[view.x])} />;
  const valueAxis = horizontal
    ? <XAxis type="number" tick={TICK} axisLine={false} tickLine={false} tickFormatter={(v) => formatTick(v, valueCol)} />
    : <YAxis tick={TICK} axisLine={false} tickLine={false} width={56} tickFormatter={(v) => formatTick(v, valueCol)} />;
  const emphasisLabel = ({ x, y, width, height: h, index, value }) => {
    if (index !== hi) return null;
    return horizontal
      ? <text x={x + width + 6} y={y + h / 2} dy={4} fontSize={11} fontWeight={600} fill={INK} style={{ fontFamily: 'var(--display)' }}>{formatCell(value, valueCol)}</text>
      : <text x={x + width / 2} y={y - 6} textAnchor="middle" fontSize={11} fontWeight={600} fill={INK} style={{ fontFamily: 'var(--display)' }}>{formatCell(value, valueCol)}</text>;
  };

  return (
    <div>
      <Headline view={view} cols={cols} rows={rows} />
      <ResponsiveContainer width="100%" height={height}>
        <ComposedChart data={rows} layout={horizontal ? 'vertical' : 'horizontal'} barCategoryGap="28%" barGap={2}
          margin={{ top: 18, right: horizontal ? 56 : 8, bottom: 0, left: 0 }}>
          <CartesianGrid stroke={GRID} horizontal={!horizontal} vertical={horizontal} />
          {categoryAxis}
          {valueAxis}
          <Tooltip cursor={{ fill: 'rgba(0,133,85,.05)' }} content={<ChartTooltip xCol={cols[view.x]} cols={cols} colorOf={colorOf} />} />
          {ys.map((y, i) => (
            <RBar key={y} dataKey={y} name={y} fill={SERIES[i]} stackId={view.stacked ? 's' : undefined}
              stroke={SURFACE} strokeWidth={view.stacked ? 2 : 0} maxBarSize={24} isAnimationActive={ANIMATE} animationDuration={500}
              radius={view.stacked && i < ys.length - 1 ? 0 : horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]}>
              {single && hi >= 0 && rows.map((_, j) => <Cell key={j} fill={j === hi ? SERIES[0] : MUTED} />)}
              {single && hi >= 0 && <LabelList dataKey={y} content={emphasisLabel} />}
            </RBar>
          ))}
          {view.reference && (
            <RLine dataKey={view.reference} name={view.reference} stroke={AXIS_TEXT} strokeWidth={1.5} strokeDasharray="4 4"
              dot={false} activeDot={false} isAnimationActive={false} legendType="none" />
          )}
        </ComposedChart>
      </ResponsiveContainer>
      <SeriesLegend names={view.reference ? [...ys, view.reference] : ys} cols={cols} colors={[...SERIES.slice(0, ys.length), AXIS_TEXT]} />
    </div>
  );
}

/** One ranked series: each row is label | bar on a light track | value, with a reference tick where one is set. */
function BarList({ view, cols, rows }) {
  const y = view.y[0];
  const max = Math.max(1e-9, ...rows.map((r) => Math.max(r[y] || 0, view.reference ? r[view.reference] || 0 : 0)));
  const hi = emphasisIndex(view.emphasis, rows, y);
  return (
    <div>
      <Headline view={view} cols={cols} rows={rows} />
      <div className="bar-list" role="list">
        {rows.map((r, i) => {
          const v = r[y] || 0;
          const ref = view.reference ? r[view.reference] : null;
          const tip = `${formatCell(r[view.x], cols[view.x])}: ${formatCell(r[y], cols[y])}`
            + (ref != null ? ` · ${cols[view.reference]?.label || view.reference} ${formatCell(ref, cols[view.reference])}` : '');
          return (
            <div key={i} className={'bar-list-row' + (hi >= 0 && i !== hi ? ' muted' : '')} role="listitem" title={tip}>
              <span className="bar-list-label">{formatCell(r[view.x], cols[view.x])}</span>
              <span className="bar-list-track">
                <span className="bar-list-fill" style={{ width: (v / max) * 100 + '%' }} />
                {ref != null && <span className="bar-list-ref" style={{ left: (ref / max) * 100 + '%' }} />}
              </span>
              <span className="bar-list-value"><Figure value={r[y]} col={cols[y]} /></span>
            </div>
          );
        })}
      </div>
      {view.reference && (
        <div className="series-legend"><span><i className="ref" />{cols[view.reference]?.label || view.reference}</span></div>
      )}
    </div>
  );
}
