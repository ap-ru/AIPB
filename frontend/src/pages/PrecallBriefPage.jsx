import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  ResponsiveContainer, LineChart, Line, BarChart, Bar, ComposedChart, XAxis, YAxis, Tooltip, Legend, CartesianGrid,
} from 'recharts';
import { useApi } from '../lib/useApi.js';
import { Loading, ErrorBox } from '../components/common.jsx';
import TopBar from '../components/TopBar.jsx';
import Icon from '../components/Icon.jsx';

// CM&A workspace · Pre-call Brief: one static brief per client, read from the source collections (see PrecallController).

const DAY = 864e5;
const SERIES = ['#008555', '#1b5e8c', '#d93d00', '#b86e00', '#5b4b8a', '#0f8a3c'];

// ------------------------------------------------------------------ formatting
const money = (v) => {
  if (v == null || Number.isNaN(Number(v))) return '–';
  const n = Number(v);
  const a = Math.abs(n);
  const s = a >= 1e9 ? `$${(a / 1e9).toFixed(2)}bn` : a >= 1e6 ? `$${(a / 1e6).toFixed(1)}m` : a >= 1e3 ? `$${(a / 1e3).toFixed(0)}k` : `$${a.toFixed(0)}`;
  return n < 0 ? `(${s})` : s;
};
const axisMoney = (v) => (Math.abs(v) >= 1e9 ? `${(v / 1e9).toFixed(1)}bn` : Math.abs(v) >= 1e6 ? `${(v / 1e6).toFixed(0)}m` : `${(v / 1e3).toFixed(0)}k`);
const day = (iso) => (iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }) : '–');
const dayTime = (iso) => (iso ? new Date(iso).toLocaleString('en-US', { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }) + ' UTC' : '–');
const mon = (iso) => new Date(iso).toLocaleDateString('en-US', { month: 'short', year: '2-digit', timeZone: 'UTC' });
const pct = (v, n = 1) => (v == null ? '–' : `${Number(v).toFixed(n)}%`);
const daysBetween = (a, b) => Math.round((new Date(b) - new Date(a)) / DAY);
const sum = (rows, f) => rows.reduce((s, r) => s + (Number(f(r)) || 0), 0);

const FIN_KPIS = [
  ['revenue', 'Revenue', 'usd'], ['grossprofit', 'Gross profit', 'usd'], ['ebitda', 'EBITDA', 'usd'], ['netincome', 'Net income', 'usd'],
  ['totalopexpenses', 'Operating expenses', 'usd'], ['interestexpense', 'Interest expense', 'usd'], ['netcashfromoperativdirect', 'Operating cash flow', 'usd'],
  ['dividendscommon', 'Common dividends', 'usd'], ['cashandequivs', 'Cash & equivalents', 'usd'], ['totaldebt', 'Total debt', 'usd'],
  ['debt_to_ebitda_rt', 'Debt / EBITDA', 'x'], ['ebitda_to_int_exp_cpltd_rt', 'EBITDA / interest', 'x'],
  ['dso_sp_num', 'DSO', 'days'], ['dio_sp_num', 'DIO', 'days'], ['dpo_sp_num', 'DPO', 'days'],
];
const fmtKpi = (v, kind) => (v == null ? '–' : kind === 'usd' ? money(v) : kind === 'x' ? `${Number(v).toFixed(2)}x` : `${Math.round(v)}`);
const CATEGORY = { credit: 'Credit', deposit: 'Deposits', treasury: 'Treasury Management', markets: 'Global Markets', capital_markets: 'Capital Markets' };
const CLOSED = ['Closed Won', 'Closed Lost'];

// ------------------------------------------------------------------ building blocks
function Section({ title, sub, wide, children }) {
  return (
    <section className={'card pcb-card' + (wide ? ' wide' : '')}>
      <div className="pcb-head"><h3>{title}</h3>{sub && <span>{sub}</span>}</div>
      <div className="pcb-body">{children}</div>
    </section>
  );
}
function Kpi({ label, value, note }) {
  return <div className="card pcb-kpi"><div className="pcb-kpi-label">{label}</div><div className="pcb-kpi-value">{value}</div>{note && <div className="pcb-kpi-note">{note}</div>}</div>;
}
function Facts({ rows }) {
  return <dl className="ni-facts">{rows.filter(Boolean).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v ?? '–'}</dd></div>)}</dl>;
}
function Table({ cols, rows, empty = 'Nothing on file.', rowClass }) {
  if (!rows.length) return <div className="empty">{empty}</div>;
  return (
    <div className="pcb-table-wrap">
      <table className="table pcb-table">
        <thead><tr>{cols.map((c) => <th key={c[0]} className={c[2] ? 'num' : ''}>{c[0]}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={r._id || i} className={rowClass ? rowClass(r) : ''}>{cols.map((c) => <td key={c[0]} className={c[2] ? 'num' : ''}>{c[1](r)}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}
const Chart = ({ children, height = 240 }) => <div style={{ width: '100%', height }}><ResponsiveContainer>{children}</ResponsiveContainer></div>;
const Sub = ({ children }) => <h5 className="pcb-sub">{children}</h5>;
const Tag = ({ tone, children }) => <span className={'tag ' + (tone || '')}>{children}</span>;
const toneOf = (s) => ({ P1: 'red', P2: 'amber', P3: 'blue', Positive: 'green', Stable: 'blue', Negative: 'red', Breach: 'red', Watch: 'amber', Compliant: 'green' }[s] || '');

// ------------------------------------------------------------------ the page
/** Without ?client: the client list with a search bar. With it: that client's brief. */
export default function PrecallBriefPage() {
  const [sp, setSp] = useSearchParams();
  const clientId = sp.get('client');
  return clientId
    ? <ClientBrief clientId={clientId} onBack={() => setSp({})} />
    : <ClientList onPick={(id) => setSp({ client: id })} />;
}

// client list columns: header, sort value, cell
const TIER_ORDER = { Strategic: 0, Core: 1, Growth: 2 };
const LIST_COLS = [
  ['Client', (c) => c.name, (c) => <><b>{c.name}</b> <span className="ni-dim">{c.clientId}</span></>],
  ['Industry', (c) => c.industry, (c) => c.industry],
  ['Segment', (c) => c.segment, (c) => c.segment],
  ['Tier', (c) => TIER_ORDER[c.tier] ?? 9, (c) => c.tier],
  ['Region', (c) => `${c.region} ${c.city}`, (c) => `${c.region} · ${c.city}`],
  ['Relationship manager', (c) => c.relationshipManager, (c) => c.relationshipManager],
  ['Last interaction', (c) => c.lastInteractionDate || '', (c) => day(c.lastInteractionDate)],
];

function ClientList({ onPick }) {
  const list = useApi('/api/precall/clients');
  const [text, setText] = useState('');
  const [sort, setSort] = useState({ col: 0, dir: 1 });
  const t = text.trim().toLowerCase();
  const key = LIST_COLS[sort.col][1];
  const rows = (list.data || []).filter((c) => !t || [c.name, c.clientId, c.industry, c.segment, c.tier, c.region, c.city, c.relationshipManager]
    .some((v) => v && v.toLowerCase().includes(t)))
    .sort((a, b) => { const x = key(a), y = key(b); return (x < y ? -1 : x > y ? 1 : 0) * sort.dir || a.name.localeCompare(b.name); });
  // a new column sorts ascending, except dates, which start with the most recent
  const sortBy = (i) => setSort((s) => (s.col === i ? { col: i, dir: -s.dir } : { col: i, dir: LIST_COLS[i][0] === 'Last interaction' ? -1 : 1 }));
  return (
    <>
      <TopBar title="Pre-call Brief" sub="Pick a client to open their brief" />
      <div className="content">
        <div className="pcb-search">
          <Icon name="search" size={14} />
          <input autoFocus placeholder="Search clients by name, industry, segment, region or relationship manager" value={text} onChange={(e) => setText(e.target.value)} />
          {text && <button className="btn ghost small" onClick={() => setText('')} title="Clear"><Icon name="x" size={11} /></button>}
        </div>
        {list.error ? <ErrorBox error={list.error} /> : list.loading && !list.data ? <Loading label="Loading clients" /> : (
          <>
            <div className="pcb-count">{rows.length} of {list.data.length} clients</div>
            <div className="card pcb-table-wrap">
              <table className="table pcb-table">
                <thead>
                  <tr>
                    {LIST_COLS.map(([label], i) => (
                      <th key={label} aria-sort={sort.col === i ? (sort.dir > 0 ? 'ascending' : 'descending') : 'none'}>
                        <button className={'pcb-sort' + (sort.col === i ? ' on' : '')} onClick={() => sortBy(i)}>
                          {label}<span aria-hidden="true">{sort.col === i ? (sort.dir > 0 ? '▲' : '▼') : '↕'}</span>
                        </button>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((c) => (
                    <tr key={c.clientId} className="click" onClick={() => onPick(c.clientId)} title={`Open the pre-call brief for ${c.name}`}>
                      {LIST_COLS.map(([label, , cell]) => <td key={label}>{cell(c)}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
              {!rows.length && <div className="empty">No clients match “{text}”.</div>}
            </div>
          </>
        )}
      </div>
    </>
  );
}

function ClientBrief({ clientId, onBack }) {
  const q = useApi('/api/precall/' + clientId);
  const c = q.data?.client;
  return (
    <>
      <TopBar title="Pre-call Brief" sub={c ? `${c.name} · ${c.industry} · ${c.segment} · ${c.tier}` : 'Loading…'}
        left={<button className="btn ghost small" onClick={onBack} title="All clients"><Icon name="left" size={13} /></button>}
        actions={<button className="btn small" onClick={onBack}><Icon name="users" size={12} /> All clients</button>} />
      <div className="content">
        {q.error ? <ErrorBox error={q.error} /> : !q.data || c.clientId !== clientId ? <Loading label="Loading brief" /> : <Brief d={q.data} />}
      </div>
    </>
  );
}

function Brief({ d }) {
  const { asOfDate } = d;
  const asOf = new Date(asOfDate);
  const credit = d.holdings.filter((h) => h.productType === 'credit');
  const deposits = d.holdings.filter((h) => h.productType === 'deposit');
  const loan = sum(credit, (h) => h.drawnUsd);
  const commit = sum(credit, (h) => h.committedUsd);
  const dep = sum(deposits, (h) => h.balanceUsd);
  const yearAgo = new Date(asOf.getTime() - 365 * DAY);
  const ttmRevenue = d.revenue.filter((r) => new Date(r.month) >= yearAgo);
  const ttm = sum(ttmRevenue, (r) => r.totalUsd);
  const open = d.opportunities.filter((o) => !CLOSED.includes(o.stage)).sort((a, b) => b.dealSizeUsd - a.dealSizeUsd);
  const wins = d.opportunities.filter((o) => o.stage === 'Closed Won' && new Date(o.closeDate) >= yearAgo);
  const recent90 = d.activities.filter((a) => daysBetween(a.date, asOf) <= 90);
  const lastContact = d.activities[0];
  const nextMeeting = d.meetings[0];
  const lobs = [...new Set(d.holdings.map((h) => h.lob))];

  return (
    <>
      <div className="pcb-kpis">
        <Kpi label="Loan balances" value={money(loan)} note={commit ? `${money(commit)} committed · ${pct((loan / commit) * 100, 0)} utilised` : 'No credit facilities'} />
        <Kpi label="Deposit balances" value={money(dep)} note={`${deposits.length} deposit account${deposits.length === 1 ? '' : 's'}`} />
        <Kpi label="Products held" value={d.holdings.length} note={`${lobs.length} line${lobs.length === 1 ? '' : 's'} of business · TTM revenue ${money(ttm)}`} />
        <Kpi label="Activity window" value={`${recent90.length} in 90 days`}
          note={`Last contact ${lastContact ? `${daysBetween(lastContact.date, asOf)} days ago` : 'none'} · next meeting ${nextMeeting ? day(nextMeeting.meetingDate) : 'none'}`} />
      </div>
      <div className="pcb-grid">
        <Overview d={d} loan={loan} commit={commit} dep={dep} ttm={ttm} open={open} wins={wins} lobs={lobs} />
        <Profile d={d} />
        <Contacts d={d} />
        <Meetings d={d} />
        <Holdings d={d} />
        <Balances d={d} />
        <Payments d={d} />
        <Calls d={d} />
        <Ratings d={d} />
        <Financials d={d} />
        <Trajectory d={d} />
        <Capital d={d} loan={loan} commit={commit} />
        <RelationshipKpis d={d} />
        <RevenueByCategory d={d} />
        <Industry d={d} />
        <Coverage d={d} ttmRevenue={ttmRevenue} open={open} />
        <SourceRegister d={d} />
      </div>
    </>
  );
}

// ------------------------------------------------------------------ sections
function Overview({ d, loan, commit, dep, ttm, open, wins, lobs }) {
  const c = d.client;
  const since = c.relationshipSince ? new Date(c.relationshipSince).getUTCFullYear() : null;
  const share = c.walletSizeUsd ? (ttm / c.walletSizeUsd) * 100 : null;
  const signals = d.signals.filter((s) => s.severity !== 'P3').slice(0, 3);
  const narrative = `${c.name} is a ${c.tier} ${c.segment} client in ${c.industry}, headquartered in ${c.city}${since ? `, banked since ${since}` : ''}. `
    + `Citizens provides ${d.holdings.length} products across ${lobs.join(', ')}. `
    + (commit ? `Lending stands at ${money(loan)} drawn of ${money(commit)} committed (${pct((loan / commit) * 100, 0)} utilised); ` : 'There are no credit facilities; ')
    + `deposits are ${money(dep)}. Trailing-twelve-month revenue to Citizens is ${money(ttm)}${share != null ? ` against an estimated wallet of ${money(c.walletSizeUsd)} (${pct(share, 0)} share)` : ''}. `
    + `Relationship health is ${c.healthScore}/100; internal rating ${c.internalRatingGrade} (${c.internalRatingLabel}), external ${c.externalRating}; KYC ${c.kycStatus.toLowerCase()}. `
    + `${open.length} open opportunit${open.length === 1 ? 'y' : 'ies'} worth ${money(sum(open, (o) => o.dealSizeUsd))} in the pipeline.`;
  return (
    <Section title="Relationship overview" sub={`RM ${c.relationshipManager}`} wide>
      <Sub>Relationship narrative</Sub>
      <p className="pcb-narrative">{narrative}</p>
      <div className="pcb-two">
        <div>
          <Sub>Open priorities</Sub>
          <ul className="pcb-list">
            {open.slice(0, 4).map((o) => (
              <li key={o._id}><b>{o.name}</b> <Tag>{o.stage}</Tag><br /><span>{money(o.dealSizeUsd)} · {o.lob} · close {day(o.closeDate)}{o.nextStep ? ` · next: ${o.nextStep}` : ''}</span></li>
            ))}
            {signals.map((s) => (
              <li key={s._id}><b>{s.title}</b> <Tag tone={toneOf(s.severity)}>{s.severity} {s.category}</Tag><br /><span>{s.summary}</span></li>
            ))}
            {!open.length && !signals.length && <li className="ni-dim">No open opportunities or signals.</li>}
          </ul>
        </div>
        <div>
          <Sub>Most recent wins</Sub>
          <ul className="pcb-list">
            {wins.slice(0, 5).map((o) => (
              <li key={o._id}><b>{o.name}</b> <Tag tone="green">Won {day(o.closeDate)}</Tag><br /><span>{o.productName} · {money(o.dealSizeUsd)} · expected revenue {money(o.expectedRevenueUsd)}</span></li>
            ))}
            {!wins.length && <li className="ni-dim">No wins in the last 12 months.</li>}
          </ul>
        </div>
      </div>
      <div className="pcb-note">Narrative written from the data on this page.</div>
    </Section>
  );
}

function Profile({ d }) {
  const c = d.client;
  const p = d.profile || { attributes: {} };
  const a = p.attributes || {};
  return (
    <Section title="Company profile and ownership" sub={p.privatePublicIndicator || 'Type not supplied'}>
      <Facts rows={[
        ['Legal name', p.companyName || c.name], ['Industry', `${c.industry}${a.naics_description ? ` · ${a.naics_description} (NAICS ${a.NAICS})` : ''}`],
        ['Headquarters', `${a.hq_city || c.city}, ${a.hq_country || c.country}`], ['Region', c.region],
        ['Annual revenue', money(a.annual_revenue ?? c.annualRevenueUsd)], ['Employees', (a.employee_size ?? c.employees)?.toLocaleString('en-US')],
        a.ticker && ['Ticker', a.ticker], a.MARKETCAP && ['Market cap', money(a.MARKETCAP)],
        a.HIGH_52_WEEK && ['52-week high', `$${a.HIGH_52_WEEK}`], ['Citizens bank role', a.bank_role],
        ['ESG score', c.esgScore], ['Client since', day(c.relationshipSince)],
      ]} />
      <Sub>Ownership</Sub>
      <Table cols={[['Holder', (o) => o.holderName], ['Type', (o) => o.holderType], ['Stake', (o) => pct(o.ownershipPct), true]]}
        rows={d.ownership} rowClass={(o) => (o.isControlling ? 'pcb-hi' : '')} empty="No ownership on file." />
    </Section>
  );
}

function Contacts({ d }) {
  const rank = { 'Decision Maker': 0, Influencer: 1, Finance: 2, Treasury: 3 };
  const rows = d.contacts.slice().sort((a, b) => (rank[a.role] ?? 9) - (rank[b.role] ?? 9) || (b.isPrimary ? 1 : 0) - (a.isPrimary ? 1 : 0));
  return (
    <Section title="Key executives, contacts and decision makers" sub={`${d.contacts.length} contacts`}>
      <Table cols={[
        ['Name', (r) => <>{r.name}{r.isPrimary && <Tag tone="solid">Primary</Tag>}</>], ['Title', (r) => r.title],
        ['Role', (r) => <Tag tone={r.role === 'Decision Maker' ? 'pink' : ''}>{r.role}</Tag>], ['Last contact', (r) => day(r.lastContactDate)],
      ]} rows={rows} />
      <div className="pcb-note">{d.sourceContacts.length} rows in the source workbook's contact list, which includes former contacts and duplicates.</div>
    </Section>
  );
}

function Meetings({ d }) {
  return (
    <Section title="Upcoming meetings and logged activity" sub="History of interactions">
      <Sub>Upcoming</Sub>
      {d.meetings.length ? d.meetings.map((m) => (
        <div key={m._id} className="pcb-meeting">
          <div><b>{m.purpose}</b> <Tag>{m.meetingType}</Tag></div>
          <div className="pcb-meta">{dayTime(m.meetingDate)} · {m.durationMinutes} min · {m.location}</div>
          <div className="pcb-meta">Citizens: {m.citizensAttendees.join(', ')} · Client: {m.clientAttendees.join(', ') || '–'}</div>
          <ul>{m.agenda.map((x) => <li key={x}>{x}</li>)}</ul>
        </div>
      )) : <div className="empty">No meetings scheduled.</div>}
      <Sub>Logged activity</Sub>
      <Table cols={[['Date', (a) => day(a.date)], ['Type', (a) => a.type], ['Summary', (a) => a.summary], ['Owner', (a) => a.owner]]} rows={d.activities.slice(0, 8)} />
    </Section>
  );
}

function Holdings({ d }) {
  const amount = (h) => (h.productType === 'credit' ? `${money(h.drawnUsd)} of ${money(h.committedUsd)}` : h.productType === 'deposit' ? money(h.balanceUsd) : h.notionalUsd ? `${money(h.notionalUsd)} notional` : '–');
  return (
    <Section title="Product holdings" sub={`${d.holdings.length} holdings`}>
      <Table cols={[
        ['Product', (h) => h.productName], ['Line of business', (h) => h.lob], ['Status', (h) => h.status],
        ['Amount', amount, true], ['Opened', (h) => day(h.openedDate)], ['Matures', (h) => (h.maturityDate ? day(h.maturityDate) : '–')],
      ]} rows={d.holdings} />
    </Section>
  );
}

function Balances({ d }) {
  const data = d.balanceHistory.map((b) => ({ m: b.isCurrent ? 'Today' : mon(b.asOfDate), Loans: b.loanBalanceUsd, Commitment: b.loanCommitmentUsd, Deposits: b.depositBalanceUsd }));
  return (
    <Section title="Loan and deposit balances" sub="Last 24 months">
      <Chart>
        <LineChart data={data} margin={{ top: 8, right: 12, left: 4, bottom: 0 }}>
          <CartesianGrid stroke="#e2e2d8" vertical={false} />
          <XAxis dataKey="m" tick={{ fontSize: 11 }} interval={3} />
          <YAxis tickFormatter={axisMoney} tick={{ fontSize: 11 }} width={56} />
          <Tooltip formatter={(v) => money(v)} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line dataKey="Loans" stroke={SERIES[0]} strokeWidth={2} dot={false} />
          <Line dataKey="Commitment" stroke={SERIES[0]} strokeDasharray="4 4" dot={false} />
          <Line dataKey="Deposits" stroke={SERIES[1]} strokeWidth={2} dot={false} />
        </LineChart>
      </Chart>
    </Section>
  );
}

function Payments({ d }) {
  const inc = d.payments.filter((p) => p.direction === 'Incoming');
  const out = d.payments.filter((p) => p.direction === 'Outgoing');
  const net = sum(inc, (p) => p.amountUsd) - sum(out, (p) => p.amountUsd);
  const cols = [['Date', (p) => day(p.paymentDate)], ['Counterparty', (p) => <>{p.counterparty} <span className="ni-dim">· {p.counterpartyType}</span></>],
    ['Channel', (p) => p.channel + (p.currency !== 'USD' ? ` · ${p.currency}` : '')], ['Amount', (p) => money(p.amountUsd), true]];
  return (
    <Section title="Largest incoming and outgoing" sub="Last 30 days">
      <div className="pcb-flow">
        <span>In {money(sum(inc, (p) => p.amountUsd))} ({inc.length})</span>
        <span>Out {money(sum(out, (p) => p.amountUsd))} ({out.length})</span>
        <span className={net < 0 ? 'pcb-neg' : 'pcb-pos'}>Net {net < 0 ? '−' : '+'}{money(Math.abs(net))}</span>
      </div>
      <Sub>Incoming</Sub>
      <Table cols={cols} rows={inc.slice(0, 5)} empty="No incoming payments." />
      <Sub>Outgoing</Sub>
      <Table cols={cols} rows={out.slice(0, 5)} empty="No outgoing payments." />
    </Section>
  );
}

function Calls({ d }) {
  const calls = d.events.filter((e) => e.channel === 'Call');
  return (
    <Section title="Call history" sub={`${calls.length} calls`}>
      <Table cols={[['Date', (e) => day(e.eventDate)], ['With', (e) => e.attendee || '–'], ['Purpose', (e) => e.purpose], ['Outcome', (e) => e.outcome], ['Banker', (e) => e.owner]]}
        rows={calls} empty="No calls logged." />
    </Section>
  );
}

function Ratings({ d }) {
  // the flag is blank when the client has a single legal entity: then that entity is the primary one
  const primaryId = (d.ratings.find((r) => r.primaryLegalEntityFlag === 'Y') || d.ratings[0])?.involvedPartyId;
  const primary = d.ratings.filter((r) => r.involvedPartyId === primaryId);
  const rows = primary.map((r, i) => ({ ...r, prev: i ? primary[i - 1].pdRatingCode : null })).reverse();
  const entities = new Set(d.ratings.map((r) => r.involvedPartyId)).size;
  return (
    <Section title="Credit rating changes" sub={`External ${d.client.externalRating} · internal ${d.client.internalRatingGrade} (${d.client.internalRatingLabel})`}>
      <Table cols={[
        ['Quarter', (r) => day(r.measurementPeriod)], ['PD grade', (r) => r.pdRatingCode, true],
        ['Change', (r) => (r.prev == null ? '–' : r.pdRatingCode > r.prev ? <span className="pcb-neg">▼ worse by {r.pdRatingCode - r.prev}</span> : r.pdRatingCode < r.prev ? <span className="pcb-pos">▲ better by {r.prev - r.pdRatingCode}</span> : 'No change')],
        ['PD', (r) => pct(r.defaultProbability * 100, 2), true], ['Source', (r) => r.ratingSource], ['Rated', (r) => day(r.pdRatingDate)],
      ]} rows={rows} empty="No rating history." />
      <div className="pcb-note">Primary legal entity shown; {entities} legal entit{entities === 1 ? 'y' : 'ies'} rated in total. Higher grade = higher risk.</div>
    </Section>
  );
}

/** The financial KPIs pivoted to one column per period (fiscal years, then TTM). */
function finPeriods(rows) {
  const periods = [...new Map(rows.map((r) => [`${r.statementType}|${r.statementDate}`, r])).values()]
    .sort((a, b) => new Date(a.statementDate) - new Date(b.statementDate))
    .map((r) => ({ key: `${r.statementType}|${r.statementDate}`, label: r.statementType === 'TTM' ? `TTM ${mon(r.statementDate)}` : `FY${new Date(r.statementDate).getUTCFullYear()}` }));
  const value = (metric, key) => rows.find((r) => r.metricName === metric && `${r.statementType}|${r.statementDate}` === key)?.metricValue ?? null;
  return { periods, value };
}

function Financials({ d }) {
  const { periods, value } = finPeriods(d.financialKpis);
  const src = d.financialKpis[0]?.source;
  return (
    <Section title="Company financial KPI trend" sub={src ? `Source: ${src}` : null} wide>
      <Table cols={[['Measure', (k) => k[1]], ...periods.map((p) => [p.label, (k) => fmtKpi(value(k[0], p.key), k[2]), true])]}
        rows={FIN_KPIS.filter((k) => periods.some((p) => value(k[0], p.key) != null))} empty="No financial statements on file." />
      <div className="pcb-note">Blank (–) means the source left the value empty.</div>
    </Section>
  );
}

function Trajectory({ d }) {
  const { periods, value } = finPeriods(d.financialKpis);
  const data = periods.map((p) => ({ p: p.label, Revenue: value('revenue', p.key), EBITDA: value('ebitda', p.key), Leverage: value('debt_to_ebitda_rt', p.key) }));
  return (
    <Section title="Revenue and leverage trajectory" sub="Debt / EBITDA on the right axis">
      <Chart>
        <ComposedChart data={data} margin={{ top: 8, right: 4, left: 4, bottom: 0 }}>
          <CartesianGrid stroke="#e2e2d8" vertical={false} />
          <XAxis dataKey="p" tick={{ fontSize: 11 }} />
          <YAxis yAxisId="usd" tickFormatter={axisMoney} tick={{ fontSize: 11 }} width={56} />
          <YAxis yAxisId="x" orientation="right" tickFormatter={(v) => `${v}x`} tick={{ fontSize: 11 }} width={36} />
          <Tooltip formatter={(v, n) => (n === 'Leverage' ? `${Number(v).toFixed(2)}x` : money(v))} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Bar yAxisId="usd" dataKey="Revenue" fill={SERIES[0]} radius={[3, 3, 0, 0]} />
          <Bar yAxisId="usd" dataKey="EBITDA" fill={SERIES[1]} radius={[3, 3, 0, 0]} />
          <Line yAxisId="x" dataKey="Leverage" stroke={SERIES[2]} strokeWidth={2} connectNulls />
        </ComposedChart>
      </Chart>
    </Section>
  );
}

function Capital({ d, loan, commit }) {
  const { periods, value } = finPeriods(d.financialKpis);
  const last = periods[periods.length - 1];
  const debt = last ? value('totaldebt', last.key) : null;
  const cash = last ? value('cashandequivs', last.key) : null;
  const ebitda = last ? value('ebitda', last.key) : null;
  const facilities = d.holdings.filter((h) => h.productType === 'credit');
  return (
    <Section title="Capital structure" sub={last ? `Company figures as of ${last.label}` : null}>
      <Facts rows={[
        ['Total debt', money(debt)], ['Cash & equivalents', money(cash)], ['Net debt', debt != null && cash != null ? money(debt - cash) : '–'],
        ['Debt / EBITDA', last ? fmtKpi(value('debt_to_ebitda_rt', last.key), 'x') : '–'],
        ['Net debt / EBITDA', debt != null && cash != null && ebitda ? `${((debt - cash) / ebitda).toFixed(2)}x` : '–'],
        ['Citizens share of debt', debt ? pct((loan / debt) * 100) : '–'],
      ]} />
      <Sub>Citizens credit facilities</Sub>
      <Table cols={[['Facility', (h) => h.productName], ['Drawn', (h) => money(h.drawnUsd), true], ['Committed', (h) => money(h.committedUsd), true], ['Matures', (h) => day(h.maturityDate)]]}
        rows={facilities} empty="No Citizens credit facilities." />
      {facilities.length > 0 && <div className="pcb-note">Total {money(loan)} drawn of {money(commit)}. Covenants: {d.covenants.map((cv) => `${cv.name} ${cv.status}`).join(' · ') || 'none'}.</div>}
      {d.bondDeal && (
        <>
          <Sub>Pro forma stack at the {day(d.bondDeal.pricedDate || d.bondDeal.launchDate)} bond deal</Sub>
          <Table cols={[['Instrument', (r) => r.instrument], ['Amount', (r) => money(r.amountUsd), true], ['Cum. leverage', (r) => `${r.cumulativeLeverageX}x`, true]]}
            rows={d.bondCapitalStructure} rowClass={(r) => (r.isNewIssue ? 'pcb-hi' : '')} />
        </>
      )}
    </Section>
  );
}

function RelationshipKpis({ d }) {
  const byDate = new Map();
  d.relationshipKpis.forEach((r) => {
    const k = r.asOfDate;
    if (!byDate.has(k)) byDate.set(k, { m: mon(k) });
    byDate.get(k)[r.metricName] = r.metricValue;
  });
  const data = [...byDate.values()];
  const lastRow = data[data.length - 1] || {};
  return (
    <Section title="Relationship KPI trend" sub="Monthly, last 24 months">
      <Facts rows={[
        ['RORC', pct(lastRow.RORC)], ['Adj. RORC', pct(lastRow['adj rorc'])],
        ['NII - loans & leases (month)', money(lastRow['net interest income - loans and leases'])], ['Fee income - loans (month)', money(lastRow['fee income - loans'])],
      ]} />
      <Chart height={200}>
        <LineChart data={data} margin={{ top: 12, right: 12, left: 4, bottom: 0 }}>
          <CartesianGrid stroke="#e2e2d8" vertical={false} />
          <XAxis dataKey="m" tick={{ fontSize: 11 }} interval={3} />
          <YAxis tickFormatter={(v) => `${v}%`} tick={{ fontSize: 11 }} width={40} />
          <Tooltip formatter={(v) => pct(v, 2)} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          <Line dataKey="RORC" stroke={SERIES[0]} strokeWidth={2} dot={false} />
          <Line dataKey="adj rorc" name="Adj. RORC" stroke={SERIES[3]} strokeWidth={2} dot={false} />
        </LineChart>
      </Chart>
    </Section>
  );
}

function RevenueByCategory({ d }) {
  const typeOf = Object.fromEntries(d.holdings.map((h) => [h.holdingId, CATEGORY[h.productType] || h.productType]));
  const cats = new Set();
  const byMonth = new Map();
  d.revenue.forEach((r) => {
    const cat = typeOf[r.holdingId] || 'Other';
    cats.add(cat);
    const k = r.month;
    if (!byMonth.has(k)) byMonth.set(k, { m: mon(k) });
    const row = byMonth.get(k);
    row[cat] = (row[cat] || 0) + r.totalUsd;
  });
  const data = [...byMonth.values()].slice(-12);
  const list = [...cats];
  const totals = list.map((cat) => [cat, sum(data, (r) => r[cat])]).sort((a, b) => b[1] - a[1]);
  return (
    <Section title="Revenue by product category" sub="Last 12 closed months">
      <Chart>
        <BarChart data={data} margin={{ top: 8, right: 12, left: 4, bottom: 0 }}>
          <CartesianGrid stroke="#e2e2d8" vertical={false} />
          <XAxis dataKey="m" tick={{ fontSize: 11 }} interval={1} />
          <YAxis tickFormatter={axisMoney} tick={{ fontSize: 11 }} width={50} />
          <Tooltip formatter={(v) => money(v)} />
          <Legend wrapperStyle={{ fontSize: 12 }} />
          {totals.map(([cat], i) => <Bar key={cat} dataKey={cat} stackId="r" fill={SERIES[i % SERIES.length]} />)}
        </BarChart>
      </Chart>
      <div className="pcb-note">{totals.map(([cat, v]) => `${cat} ${money(v)}`).join(' · ')}</div>
    </Section>
  );
}

function Industry({ d }) {
  const ind = d.industry;
  const me = d.client.clientId;
  const peers = d.peers;
  const rank = peers.findIndex((p) => p.clientId === me) + 1;
  return (
    <Section title="Industry context" sub={d.client.industry}>
      {ind ? (
        <>
          <p className="pcb-narrative"><Tag tone={toneOf(ind.outlook)}>{ind.outlook} outlook</Tag> {ind.summary}</p>
          <Facts rows={[['Sector revenue growth (12m)', pct(ind.revenueGrowthPct)], ['Typical EBITDA margin', pct(ind.ebitdaMarginPct, 0)], ['Spec-grade default rate', pct(ind.defaultRatePct)]]} />
          <div className="pcb-two">
            <div><Sub>Key drivers</Sub><ul className="pcb-bullets">{ind.keyDrivers.map((x) => <li key={x}>{x}</li>)}</ul></div>
            <div><Sub>Key risks</Sub><ul className="pcb-bullets">{ind.keyRisks.map((x) => <li key={x}>{x}</li>)}</ul></div>
          </div>
        </>
      ) : <div className="empty">No industry view on file.</div>}
      <Sub>Peers in the Citizens book · #{rank} of {peers.length} by revenue</Sub>
      <Table cols={[['Client', (p) => p.name], ['Revenue', (p) => money(p.annualRevenueUsd), true], ['Rating', (p) => p.externalRating], ['Health', (p) => p.healthScore, true]]}
        rows={peers} rowClass={(p) => (p.clientId === me ? 'pcb-hi' : '')} />
      {ind && <div className="pcb-note">{ind.source}, {day(ind.asOfDate)}.</div>}
    </Section>
  );
}

function Coverage({ d, ttmRevenue, open }) {
  const LOBS = ['Corporate Banking', 'Commercial Lending', 'Capital Markets', 'Global Markets'];
  const rows = LOBS.map((lob) => ({
    _id: lob, lob,
    products: d.holdings.filter((h) => h.lob === lob).length,
    revenue: sum(ttmRevenue.filter((r) => r.lob === lob), (r) => r.totalUsd),
    pipeline: sum(open.filter((o) => o.lob === lob), (o) => o.dealSizeUsd),
    opps: open.filter((o) => o.lob === lob).length,
  }));
  const engaged = rows.filter((r) => r.products || r.opps).length;
  return (
    <Section title="Coverage team and cross-LOB engagement" sub={`${engaged} of ${LOBS.length} lines of business engaged`}>
      <Table cols={[['Banker', (t) => t.employeeName], ['Coverage role', (t) => t.coverageRole], ['Title', (t) => t.employeeTitle], ['Division', (t) => t.employeeDivision]]}
        rows={d.coverage} empty="No coverage team on file." />
      <Sub>Engagement by line of business</Sub>
      <Table cols={[
        ['Line of business', (r) => r.lob], ['Products', (r) => r.products, true], ['TTM revenue', (r) => money(r.revenue), true],
        ['Open pipeline', (r) => (r.opps ? `${money(r.pipeline)} (${r.opps})` : '–'), true],
      ]} rows={rows} rowClass={(r) => (!r.products && !r.opps ? 'pcb-gap' : '')} />
      <div className="pcb-note">Greyed rows are lines of business with no products and no open opportunities: cross-sell gaps.</div>
    </Section>
  );
}

function SourceRegister({ d }) {
  const as = day(d.asOfDate);
  const rows = [
    ['Top KPIs, product holdings, cross-LOB', 'holdings', d.holdings.length, `Current as of ${as}`],
    ['Relationship overview', 'clients · opportunities · signals', 1 + d.opportunities.length + d.signals.length, `As of ${as}`],
    ['Company profile', 'precall_company_profiles', d.profile ? 1 : 0, d.profile?.sourceWorkbook || '–'],
    ['Ownership', 'precall_ownership', d.ownership.length, d.ownership[0] ? `Snapshot ${day(d.ownership[0].asOfDate)}` : '–'],
    ['Contacts and decision makers', 'contacts · precall_company_contacts', d.contacts.length + d.sourceContacts.length, 'CRM and source workbook'],
    ['Upcoming meetings', 'precall_upcoming_meetings', d.meetings.length, 'Next 45 days'],
    ['Logged activity', 'activities', d.activities.length, 'Last 180 days'],
    ['Call history', 'precall_company_event_history', d.events.length, 'Source workbook'],
    ['Loan and deposit balances', 'precall_balance_history', d.balanceHistory.length, '24 months to today'],
    ['Largest incoming and outgoing', 'precall_payment_flows', d.payments.length, 'Last 30 days'],
    ['Credit rating changes', 'precall_company_risk_rating_history', d.ratings.length, 'Last 6 quarters'],
    ['Financial KPIs, revenue and leverage', 'precall_company_financial_kpis', d.financialKpis.length, d.financialKpis[0]?.source || '–'],
    ['Capital structure', 'holdings · covenants · precall_company_financial_kpis' + (d.bondDeal ? ' · hy_capital_structure' : ''), d.covenants.length + d.bondCapitalStructure.length, 'Latest statements'],
    ['Relationship KPIs', 'precall_citizens_relationship_kpis', d.relationshipKpis.length, 'Monthly, 24 months'],
    ['Revenue by product category', 'revenue_monthly', d.revenue.length, 'Closed months'],
    ['Industry context', 'precall_industry_context · clients', 1 + d.peers.length, d.industry ? day(d.industry.asOfDate) : '–'],
    ['Coverage team', 'precall_citizens_coverage', d.coverage.length, 'Source workbook'],
  ].map(([section, src, n, period], i) => ({ _id: i, section, src, n, period }));
  return (
    <Section title="Source register" sub={`Data as of ${as} · synthetic seed data`} wide>
      <Table cols={[['Section', (r) => r.section], ['Collection(s)', (r) => <code>{r.src}</code>], ['Rows', (r) => r.n, true], ['Period / source', (r) => r.period]]} rows={rows} />
    </Section>
  );
}
