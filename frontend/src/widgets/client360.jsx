import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bar as RBar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useApi } from '../lib/useApi.js';
import { qs, filters } from '../lib/api.js';
import { money, pct, shortDate, timeAgo, healthColor, LOB_COLORS } from '../lib/format.js';
import { Async, Bar, CatTag, Gauge, Loading, ErrorBox, Sev } from '../components/common.jsx';
import Icon from '../components/Icon.jsx';

function Tile({ label, value, sub, color }) {
  return (
    <div className="kpi">
      <div className="lbl" style={{ marginTop: 0 }}>{label}</div>
      <div className="val" style={{ fontSize: 24, color }}>{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  );
}

function Section({ title, span, children, right }) {
  return (
    <div className="card" style={{ gridColumn: 'span ' + span }}>
      <div className="card-head"><h3>{title}</h3><div style={{ marginLeft: 'auto' }}>{right}</div></div>
      <div className="card-body">{children}</div>
    </div>
  );
}

function SignalRow({ s }) {
  return (
    <div className="list-item">
      <Sev s={s.severity} />
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ fontWeight: 600, fontSize: 13, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title}</div>
        <div className="muted" style={{ fontSize: 12 }}>{s.type} · {s.source}</div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <CatTag c={s.category} />
        <div className="mono dim" style={{ fontSize: 10, marginTop: 4 }}>{timeAgo(s.detectedAt)}</div>
      </div>
    </div>
  );
}

/** The full relationship view for one client, from the /api/clients/{id} bundle. Shared by the Client 360 page and widget. */
export function ClientProfile({ data }) {
  const { client: c, opportunities, signals, pipeline, revenueByLob, peerAvgShareOfWallet, peerAvgHealth, peerCount } = data;
  const yoy = c.revenuePriorYtd ? (c.revenueYtd - c.revenuePriorYtd) / c.revenuePriorYtd : 0;
  const open = opportunities.filter((o) => !o.stage.startsWith('Closed'));
  const activeSignals = signals.filter((s) => s.stage !== 'Resolved');
  const quarters = c.revenueTrend.map((v, i) => ({ q: 'Q-' + (c.revenueTrend.length - i), v }));
  const lobData = Object.entries(revenueByLob).map(([lob, v]) => ({ lob, v }));

  return (
    <>
      <div className="card glow" style={{ marginBottom: 16 }}>
        <div className="hero">
          <Gauge value={c.healthScore} />
          <div style={{ flex: 1, minWidth: 260 }}>
            <div className="row wrap">
              <span className="tag solid">Client</span>
              <span className={'tag ' + (c.tier === 'Strategic' ? 'violet' : '')}>{c.tier}</span>
              <span className="tag" style={{ color: LOB_COLORS[c.segment] }}>{c.segment}</span>
              <span className={'tag ' + (c.kycStatus === 'Current' ? 'green' : c.kycStatus === 'Overdue' ? 'red' : 'amber')}>KYC {c.kycStatus}</span>
            </div>
            <div className="name" style={{ marginTop: 6 }}>{c.name}</div>
            <div className="muted" style={{ fontSize: 13 }}>
              {c.industry} · {c.city} ({c.region}) · {money(c.annualRevenue)} revenue · {c.employees.toLocaleString()} employees · client since {c.relationshipSince?.slice(0, 4)}
            </div>
          </div>
          <div className="stack" style={{ minWidth: 220 }}>
            <div className="between"><span className="label">RM</span><span style={{ fontWeight: 600 }}>{c.relationshipManager}</span></div>
            <div className="between"><span className="label">Internal rating</span><span className="mono">{c.internalRating}</span></div>
            <div className="between"><span className="label">External</span><span className="mono">{c.externalRating}</span></div>
            <div className="between"><span className="label">Next review</span><span className="mono">{shortDate(c.nextReviewDate)}</span></div>
          </div>
        </div>
      </div>

      <div className="kpis" style={{ gridTemplateColumns: 'repeat(6, minmax(0,1fr))', marginBottom: 16 }}>
        <Tile label="Revenue YTD" value={money(c.revenueYtd)} sub={<span className={yoy >= 0 ? 'up' : 'down'}>{yoy >= 0 ? '+' : ''}{pct(yoy)} vs PY</span>} />
        <Tile label="Share of wallet" value={pct(c.shareOfWallet, 0)} sub={`of ${money(c.walletSize)} · peers ${pct(peerAvgShareOfWallet, 0)}`} />
        <Tile label="Total exposure" value={money(c.totalExposure)} sub={`${pct(c.utilization, 0)} of ${money(c.committedLimit)}`} />
        <Tile label="Deposits" value={money(c.deposits)} sub="operating + liquidity" />
        <Tile label="Open pipeline" value={money(pipeline)} sub={`${open.length} opportunities`} color="var(--cyan)" />
        <Tile label="Active signals" value={activeSignals.length} sub={`${activeSignals.filter((s) => s.severity === 'P1').length} P1 · health peers ${Math.round(peerAvgHealth)} (${peerCount})`} color={activeSignals.some((s) => s.severity === 'P1') ? 'var(--red)' : undefined} />
      </div>

      <div className="grid">
        <Section title="Revenue trend · last 8 quarters" span={7}>
          <ResponsiveContainer width="100%" height={210}>
            <BarChart data={quarters}>
              <CartesianGrid stroke="#ecece4" vertical={false} />
              <XAxis dataKey="q" tick={{ fill: '#5e5a5b', fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tickFormatter={(v) => money(v, 1)} tick={{ fill: '#5e5a5b', fontSize: 11 }} axisLine={false} tickLine={false} width={56} />
              <Tooltip contentStyle={{ background: '#ffffff', border: '1px solid #e2e2d8' }} formatter={(v) => [money(v), 'Revenue']} cursor={{ fill: 'rgba(0,133,85,.06)' }} />
              <RBar dataKey="v" radius={[4, 4, 0, 0]}>
                {quarters.map((_, i) => <Cell key={i} fill={i === quarters.length - 1 ? '#008555' : '#a9d6c3'} />)}
              </RBar>
            </BarChart>
          </ResponsiveContainer>
        </Section>

        <Section title="Product revenue by LOB" span={5}>
          {lobData.map((l) => (
            <div key={l.lob} style={{ marginBottom: 12 }}>
              <div className="between" style={{ fontSize: 13 }}><span style={{ color: LOB_COLORS[l.lob] }}>{l.lob}</span><span className="mono">{money(l.v)}</span></div>
              <Bar value={l.v} max={Math.max(...lobData.map((x) => x.v), 1)} color={LOB_COLORS[l.lob]} />
            </div>
          ))}
          <div className="label" style={{ marginTop: 14, marginBottom: 6 }}>Cross-sell whitespace</div>
          <div className="triggers">{c.crossSell.length ? c.crossSell.map((p) => <span key={p} style={{ color: 'var(--green)', borderColor: 'rgba(0,133,85,.4)' }}>+ {p}</span>) : <span>Fully penetrated</span>}</div>
        </Section>

        <Section title="Product holdings" span={7}>
          <table className="table">
            <thead><tr><th>Product</th><th>LOB</th><th className="num">Balance / notional</th><th className="num">Revenue</th><th>Status</th></tr></thead>
            <tbody>
              {c.products.map((p, i) => (
                <tr key={i}>
                  <td style={{ fontWeight: 600 }}>{p.name}</td>
                  <td style={{ color: LOB_COLORS[p.lob], fontSize: 12 }}>{p.lob}</td>
                  <td className="num">{money(p.balance)}</td>
                  <td className="num">{money(p.revenue)}</td>
                  <td><span className={'tag ' + (p.status === 'Active' ? 'green' : p.status === 'Maturing' ? 'amber' : 'blue')}>{p.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title="Covenants" span={5}>
          {c.covenants.map((cv) => (
            <div key={cv.name} style={{ marginBottom: 12 }}>
              <div className="between"><span style={{ fontWeight: 600 }}>{cv.name}</span>
                <span className={'tag ' + (cv.status === 'Compliant' ? 'green' : cv.status === 'Watch' ? 'amber' : 'red')}>{cv.status}</span></div>
              <div className="between muted mono" style={{ fontSize: 11, margin: '3px 0 4px' }}><span>{cv.threshold} · actual {cv.actual}</span><span>{cv.headroomPct.toFixed(1)}% headroom</span></div>
              <Bar value={Math.max(0, cv.headroomPct)} max={60} color={cv.status === 'Compliant' ? 'var(--green)' : cv.status === 'Watch' ? 'var(--amber)' : 'var(--red)'} />
            </div>
          ))}
        </Section>

        <Section title={`Opportunities · ${open.length} open`} span={7}>
          <table className="table">
            <thead><tr><th>Opportunity</th><th>Stage</th><th className="num">Revenue</th><th className="num">Close</th><th>Blockers</th></tr></thead>
            <tbody>
              {opportunities.slice(0, 12).map((o) => (
                <tr key={o.id}>
                  <td><div style={{ fontWeight: 600 }}>{o.product}</div><div className="muted" style={{ fontSize: 12, color: LOB_COLORS[o.lob] }}>{o.lob}</div></td>
                  <td className="mono" style={{ fontSize: 12 }}>
                    <span className={o.stage === 'Closed Won' ? 'up' : o.stage === 'Closed Lost' ? 'down' : ''}>{o.stage}</span>
                    {!o.stage.startsWith('Closed') && <div className="muted">{o.probability}% · {o.forecastCategory}</div>}
                  </td>
                  <td className="num" style={{ color: 'var(--cyan)' }}>{money(o.expectedRevenue)}</td>
                  <td className="num" style={{ color: !o.stage.startsWith('Closed') && new Date(o.closeDate) < new Date() ? 'var(--red)' : undefined }}>{shortDate(o.closeDate)}</td>
                  <td>{(o.blockers || []).filter((b) => b.status !== 'Cleared').map((b, i) => (
                    <span key={i} className={'tag ' + (b.status === 'Blocked' ? 'red' : 'amber')} style={{ marginRight: 4 }}>{b.area}</span>))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>

        <Section title={`Signals · ${activeSignals.length} active`} span={5}>
          <Async q={{ data: signals, loading: false }} empty="No signals for this client">
            {(rows) => rows.slice(0, 8).map((s) => <SignalRow key={s.id} s={s} />)}
          </Async>
        </Section>

        <Section title="Key contacts" span={4}>
          {c.contacts.map((p) => (
            <div key={p.name + p.title} className="between" style={{ padding: '7px 0', borderBottom: '1px solid var(--line)' }}>
              <div><div style={{ fontWeight: 600 }}>{p.name}</div><div className="muted" style={{ fontSize: 12 }}>{p.title}</div></div>
              <div style={{ textAlign: 'right' }}><span className="tag blue">{p.role}</span><div className="mono dim" style={{ fontSize: 10, marginTop: 3 }}>last {shortDate(p.lastContact)}</div></div>
            </div>
          ))}
        </Section>

        <Section title="Activity timeline" span={4}>
          <div className="timeline">
            {c.activities.map((a, i) => (
              <div key={i} className="ev">
                <div className="row"><span className="tag">{a.type}</span><span className="mono dim" style={{ fontSize: 11 }}>{shortDate(a.date)} · {a.owner}</span></div>
                <div style={{ fontSize: 13, marginTop: 4 }}>{a.summary}</div>
              </div>
            ))}
          </div>
        </Section>

        <Section title="Coverage team" span={4}>
          <div className="stack">
            <div className="between"><span className="label">Relationship manager</span><span>{c.relationshipManager}</span></div>
            {c.coverageTeam.map((t) => <div key={t} className="muted" style={{ fontSize: 13 }}>• {t}</div>)}
            <div className="between" style={{ marginTop: 10 }}><span className="label">NII YTD</span><span className="mono">{money(c.netInterestIncome)}</span></div>
            <div className="between"><span className="label">Fee income YTD</span><span className="mono">{money(c.feeIncome)}</span></div>
            <div className="between"><span className="label">ESG score</span><span className="mono" style={{ color: healthColor(c.esgScore) }}>{c.esgScore}</span></div>
          </div>
        </Section>
      </div>
    </>
  );
}

function ClientDetail({ clientId }) {
  const q = useApi('/api/clients/' + clientId);
  const nav = useNavigate();
  if (q.loading && !q.data) return <Loading label="Loading Client 360" />;
  if (q.error) return <ErrorBox error={q.error} />;
  return (
    <>
      <div className="row" style={{ justifyContent: 'flex-end', marginBottom: 10 }}>
        <button className="btn" onClick={() => nav('/clients/' + clientId)}><Icon name="users" size={13} /> Full view</button>
      </div>
      <ClientProfile data={q.data} />
    </>
  );
}

/**
 * Client 360 widget: the full relationship view for one client, with a picker over the book.
 * config.clientId pins the starting client; lob / region / marketId / sort scope the picker list.
 */
export function Client360Widget({ config }) {
  const sort = config.sort || 'revenue';
  const list = useApi('/api/clients' + qs({ ...filters(config), sort }));
  const [picked, setPicked] = useState(null);
  const rows = list.data || [];
  const clientId = picked || (rows.some((c) => c.id === config.clientId) ? config.clientId : rows[0]?.id);
  return (
    <div className="card-body">
      <Async q={list} empty="No clients match this widget's filters">
        {() => (
          <>
            <div className="row" style={{ marginBottom: 12 }}>
              <span className="label">Client</span>
              <select value={clientId} onChange={(e) => setPicked(e.target.value)} style={{ maxWidth: 360 }}>
                {rows.map((c) => <option key={c.id} value={c.id}>{c.name} · {c.segment}</option>)}
              </select>
              <span className="muted mono" style={{ fontSize: 11 }}>{rows.length} clients · sorted by {sort}</span>
            </div>
            <ClientDetail key={clientId} clientId={clientId} />
          </>
        )}
      </Async>
    </div>
  );
}
