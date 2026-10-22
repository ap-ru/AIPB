import { useId } from 'react';
import { Area, CartesianGrid, ComposedChart, Line as RLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { formatCell, formatTick } from '../format.js';
import { ANIMATE, AXIS_TEXT, GRID, INK, SERIES, SURFACE, TICK } from '../theme.js';
import { ChartTooltip, Headline, SeriesLegend } from '../parts.jsx';

/**
 * Change over time. One series: a 2px line over a 10% wash, its latest point dotted and labelled with its value.
 * Several: 2px lines, a legend and direct labels at the line ends. A reference column (a limit or target) is a dashed
 * ink line. The crosshair tooltip lists every series at the hovered period.
 */
export default function Line({ view, cols, rows }) {
  const gid = useId().replace(/:/g, '');
  const ys = view.y.slice(0, SERIES.length);
  const single = ys.length === 1;
  const valueCol = cols[ys[0]];
  const last = rows.length - 1;
  const showEnd = (view.emphasis ?? 'latest') === 'latest';

  const endLabel = (y) => function EndLabel({ x, y: py, index, value }) {
    if (index !== last || !showEnd) return null;
    const text = single ? formatCell(value, cols[y]) : cols[y]?.label;
    return <text x={x + 8} y={py} dy={4} fontSize={11} fontWeight={600} fill={INK} style={{ fontFamily: 'var(--display)' }}>{text}</text>;
  };
  const endDot = (color) => function EndDot({ cx, cy, index }) {
    if (index !== last || !showEnd || cx == null || cy == null) return null;
    return <circle cx={cx} cy={cy} r={4} fill={color} stroke={SURFACE} strokeWidth={2} />;
  };
  const colorOf = Object.fromEntries([...ys.map((y, i) => [y, SERIES[i]]), [view.reference, AXIS_TEXT]]);
  const marks = ys.map((y, i) => {
    const common = {
      dataKey: y, name: y, stroke: SERIES[i], strokeWidth: 2, strokeLinejoin: 'round', strokeLinecap: 'round',
      dot: endDot(SERIES[i]), activeDot: { r: 4, stroke: SURFACE, strokeWidth: 2 }, label: endLabel(y), isAnimationActive: ANIMATE, animationDuration: 600,
    };
    return single ? <Area key={y} {...common} type="monotone" fill={`url(#${gid})`} /> : <RLine key={y} {...common} type="monotone" />;
  });

  return (
    <div>
      <Headline view={view} cols={cols} rows={rows} />
      <ResponsiveContainer width="100%" height={240}>
        <ComposedChart data={rows} margin={{ top: 12, right: single ? 64 : 120, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id={gid} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={SERIES[0]} stopOpacity={0.16} />
              <stop offset="100%" stopColor={SERIES[0]} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey={view.x} tick={TICK} axisLine={{ stroke: GRID }} tickLine={false} minTickGap={28}
            tickFormatter={(v) => formatTick(v, cols[view.x])} />
          <YAxis tick={TICK} axisLine={false} tickLine={false} width={56} tickFormatter={(v) => formatTick(v, valueCol)} />
          <Tooltip cursor={{ stroke: AXIS_TEXT, strokeWidth: 1, strokeOpacity: 0.4 }}
            content={<ChartTooltip xCol={cols[view.x]} cols={cols} colorOf={colorOf} line />} />
          {view.reference && (
            <RLine dataKey={view.reference} name={view.reference} stroke={AXIS_TEXT} strokeWidth={1.5} strokeDasharray="4 4"
              dot={false} activeDot={false} isAnimationActive={false} type="stepAfter" />
          )}
          {marks}
        </ComposedChart>
      </ResponsiveContainer>
      <SeriesLegend names={view.reference ? [...ys, view.reference] : ys} cols={cols}
        colors={[...SERIES.slice(0, ys.length), AXIS_TEXT]} line />
    </div>
  );
}
