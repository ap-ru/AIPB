import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from '../lib/useApi.js';
import { qs, filters } from '../lib/api.js';
import { money, pct, formatValue, healthColor, LOB_COLORS } from '../lib/format.js';
import { Async, Bar, Sparkline } from '../components/common.jsx';

const TAG_COLOR = { REVENUE: '', CREDIT: 'blue', CLIENTS: '', DEPOSITS: 'blue', HEALTH: 'amber' };

/** Row of book-level KPI tiles. */
export function KpiStrip({ config }) {
  const q = useApi('/api/kpis' + qs(filters(config)));
  return (
    <Async q={q}>
      {(kpis) => (
        <div className="kpis">
          {kpis.map((k) => {
            const pos = (k.delta || 0) >= 0;
            return (
              <div className="kpi" key={k.label}>
                <div className="between">
                  <span className={'tag ' + (TAG_COLOR[k.tag] || '')}>{k.tag}</span>
                  {k.delta !== undefined && <span className={'mono ' + (pos ? 'up' : 'down')} style={{ fontSize: 12, fontWeight: 700 }}>
                    {(pos ? '+' : '') + k.delta.toFixed(1) + '% ' + k.deltaLabel}</span>}
                </div>
                <div className="lbl">{k.label}</div>
                <div className="val">{formatValue(k.value, k.format)}</div>
                <div className="sub">{k.sub}</div>
              </div>
            );
          })}
        </div>
      )}
    </Async>
  );
}

export function ClientTable({ config }) {
  const [q, setQ] = useState('');
  const sort = config.sort || 'revenue';
  const data = useApi('/api/clients' + qs({ ...filters(config), sort, limit: config.limit || 100, q }));
  const nav = useNavigate();
  return (
    <div className="card-body" style={{ overflowX: 'auto' }}>
      <div className="row" style={{ marginBottom: 10 }}>
        <input placeholder="Search clients or industries…" value={q} onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 320 }} />
        <span className="muted mono" style={{ fontSize: 11 }}>sorted by {sort}</span>
      </div>
      <Async q={data} empty="No clients">
        {(rows) => (
          <table className="table">
            <thead>
              <tr>
                <th>Client</th><th>Segment</th><th>Tier</th><th>Health</th><th className="num">Revenue YTD</th><th>Trend (8Q)</th>
                <th className="num">Wallet</th><th>Share of wallet</th><th className="num">{sort === 'walletGap' ? 'Wallet gap' : 'Exposure'}</th>
                <th className="num">Pipeline</th><th className="num">Signals</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="click" onClick={() => nav('/clients/' + c.id)}>
                  <td><div style={{ fontWeight: 700 }}>{c.name}</div><div className="muted" style={{ fontSize: 12 }}>{c.industry} · {c.city} · {c.relationshipManager}</div></td>
                  <td style={{ color: LOB_COLORS[c.segment], fontSize: 12 }}>{c.segment}</td>
                  <td><span className={'tag ' + (c.tier === 'Strategic' ? 'violet' : c.tier === 'Core' ? '' : 'blue')}>{c.tier}</span></td>
                  <td><span className="health" style={{ color: healthColor(c.healthScore) }}>{c.healthScore}</span></td>
                  <td className="num">{money(c.revenueYtd)}</td>
                  <td><Sparkline data={c.revenueTrend} /></td>
                  <td className="num">{money(c.walletSize)}</td>
                  <td style={{ minWidth: 110 }}><div className="mono" style={{ fontSize: 11 }}>{pct(c.shareOfWallet, 0)}</div><Bar value={c.shareOfWallet} max={1} /></td>
                  <td className="num">{sort === 'walletGap' ? <span className="warn">{money(c.walletGap)}</span> : money(c.totalExposure)}</td>
                  <td className="num" style={{ color: 'var(--cyan)' }}>{money(c.pipeline)}</td>
                  <td className="num">{c.signals > 0 ? <span className="tag red">{c.signals}</span> : <span className="dim">0</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Async>
    </div>
  );
}
