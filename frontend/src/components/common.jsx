import { healthColor, CATEGORY_TAG } from '../lib/format.js';

export function Loading({ label = 'Loading' }) {
  return <div className="loading">{label.toUpperCase()}…</div>;
}

export function ErrorBox({ error }) {
  return <div className="error-box">⚠ {String(error?.message || error)}</div>;
}

/** Renders loading / error / empty states around data. */
export function Async({ q, children, empty = 'No data' }) {
  if (q.loading && !q.data) return <Loading />;
  if (q.error) return <ErrorBox error={q.error} />;
  if (!q.data || (Array.isArray(q.data) && q.data.length === 0)) return <div className="empty">{empty}</div>;
  return children(q.data);
}

export function Gauge({ value, size = 74 }) {
  const r = size / 2 - 6;
  const c = 2 * Math.PI * r;
  const col = healthColor(value);
  return (
    <div className="gauge" style={{ width: size, height: size }}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} stroke="#ecece4" strokeWidth="6" fill="none" />
        <circle cx={size / 2} cy={size / 2} r={r} stroke={col} strokeWidth="6" fill="none" strokeLinecap="round"
          strokeDasharray={`${(c * value) / 100} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
      </svg>
      <span style={{ color: col }}>{value}</span>
    </div>
  );
}

export function Sparkline({ data = [], width = 90, height = 26, color = 'var(--cyan)' }) {
  if (!data.length) return null;
  const min = Math.min(...data), max = Math.max(...data);
  const pts = data.map((v, i) => [(i / (data.length - 1 || 1)) * width, height - 2 - ((v - min) / (max - min || 1)) * (height - 4)]);
  return (
    <svg width={width} height={height}>
      <polyline points={pts.map((p) => p.join(',')).join(' ')} fill="none" stroke={color} strokeWidth="1.6" />
    </svg>
  );
}

export function Sev({ s }) {
  return <span className={'sev ' + s}>{s}</span>;
}

export function CatTag({ c }) {
  return <span className={'tag ' + (CATEGORY_TAG[c] || '')}>{c}</span>;
}

export function Bar({ value, max, color }) {
  const w = max ? Math.max(2, Math.min(100, (value / max) * 100)) : 0;
  return <div className="bar"><span style={{ width: w + '%', background: color }} /></div>;
}
