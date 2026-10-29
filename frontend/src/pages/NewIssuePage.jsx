import { useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useApi } from '../lib/useApi.js';
import { Loading, ErrorBox } from '../components/common.jsx';
import TopBar from '../components/TopBar.jsx';
import Icon from '../components/Icon.jsx';

// CM&A workspace · New Issue: the deal calendar per product tab, a historical deal search, and one deal's transaction
// details. A static page over the bank graph's Hy* labels (only High Yield has data so far).

const TABS = [
  { id: 'tlb', label: 'TLB / Pro Rata' },
  { id: 'high-yield', label: 'High Yield', market: 'High Yield' },
  { id: 'ig-dcm', label: 'IG DCM' },
  { id: 'structured', label: 'Structured Products' },
  { id: 'ecm', label: 'ECM' },
];
const tabOf = (id) => TABS.find((t) => t.id === id) || TABS[1];
const CALENDAR_DAYS = 90;
const DAY = 864e5;

// ------------------------------------------------------------------ formatting
const month = (iso) => {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString('en-US', { month: 'short', timeZone: 'UTC' }) + '-' + String(d.getUTCFullYear()).slice(2);
};
const day = (iso) => (iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }) : '–');
const usdM = (v) => (v == null ? '–' : Math.round(v / 1e6).toLocaleString('en-US'));
const money = (v) => {
  if (v == null) return '–';
  const a = Math.abs(v);
  const s = a >= 1e9 ? `$${(a / 1e9).toFixed(2)}bn` : a >= 1e6 ? `$${(a / 1e6).toFixed(1)}m` : `$${Math.round(a).toLocaleString('en-US')}`;
  return v < 0 ? `(${s})` : s;
};
const fix = (v, n) => (v == null ? '–' : Number(v).toFixed(n));
const x = (v) => (v == null ? '–' : `${Number(v).toFixed(1)}x`);
const talk = (lo, hi) => `${fix(lo, 3)}–${fix(hi, 3)}`;

// ------------------------------------------------------------------ shared pieces
function Tabs({ active, onPick, right }) {
  return (
    <div className="ni-tabs">
      {TABS.map((t) => (
        <button key={t.id} className={'ni-tab' + (t.id === active ? ' active' : '')} onClick={() => onPick(t.id)}>{t.label}</button>
      ))}
      <div className="spacer" />
      {right}
    </div>
  );
}

/** The calendar / search results grid: one row per deal; any cell opens the deal. */
function DealTable({ deals, onOpen }) {
  return (
    <div className="ni-table-wrap">
      <table className="table ni-table">
        <thead>
          <tr>
            <th>Issuer</th><th>Our role</th><th>Call</th><th className="num">Priced</th><th className="num">Size ($m)</th>
            <th className="num">Coupon (%)</th><th className="num">Yield (%)</th><th className="num">Leverage (x)</th>
          </tr>
        </thead>
        <tbody>
          {deals.map((d) => {
            const live = d.status === 'In Market';
            return (
              <tr key={d.dealId} className="click" onClick={() => onOpen(d.dealId)} title={`Open ${d.issuerName} transaction details`}>
                <td>{d.issuerName}</td>
                <td className={d.ourRole === 'n/a' ? 'ni-dim' : ''}>{d.ourRole}</td>
                <td>{d.callProtection}</td>
                <td className="num">{live ? <span className="ni-live" title={`Expected to price ${day(d.expectedPricingDate)}`}>In market</span> : month(d.pricedDate)}</td>
                <td className="num">{usdM(d.dealSizeUsd)}</td>
                <td className="num">{live ? <span className="ni-talk" title="Price talk">{talk(d.priceTalkLowPct, d.priceTalkHighPct)}</span> : fix(d.couponPct, 3)}</td>
                <td className="num">{live ? '' : fix(d.yieldPct, 2)}</td>
                <td className="num">{fix(d.totalLeverageX, 1)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {deals.length === 0 && <div className="empty">No deals match.</div>}
    </div>
  );
}

function EmptyTab({ tab }) {
  return <div className="empty soft">No {tab.label} deals yet. High Yield is the only product with data so far.</div>;
}

// ------------------------------------------------------------------ calendar (default view)
export function NewIssuePage() {
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const tab = tabOf(sp.get('tab'));
  const q = useApi(tab.market ? '/api/new-issue/deals?market=' + encodeURIComponent(tab.market) : null);
  const open = (id) => nav('/new-issue/deals/' + id);

  let body;
  if (!tab.market) body = <EmptyTab tab={tab} />;
  else if (q.loading && !q.data) body = <Loading label="Loading deals" />;
  else if (q.error) body = <ErrorBox error={q.error} />;
  else {
    const asOf = new Date(q.data.asOfDate).getTime();
    const deals = q.data.deals.filter((d) => d.status === 'In Market' || asOf - new Date(d.pricedDate).getTime() <= CALENDAR_DAYS * DAY);
    const live = deals.filter((d) => d.status === 'In Market').length;
    body = (
      <>
        <div className="ni-caption">In market calendar <span>· {live} in market, {deals.length - live} priced in the last {CALENDAR_DAYS} days</span></div>
        <DealTable deals={deals} onOpen={open} />
      </>
    );
  }
  return (
    <>
      <TopBar title="New Issue" sub="CM&A workspace · one view, every product" />
      <div className="content">
        <Tabs active={tab.id} onPick={(id) => setSp({ tab: id })}
          right={<button className="btn small" onClick={() => nav('/new-issue/search?tab=' + tab.id)}><Icon name="globe" size={12} /> Search Historical Deals</button>} />
        {body}
      </div>
    </>
  );
}

// ------------------------------------------------------------------ search historical deals
const NO_FILTERS = { text: '', role: '', dealType: '', status: '', call: '', rating: '', minSize: '', maxSize: '', minYield: '', maxYield: '', maxLev: '', from: '', to: '' };
const num = (s) => (s === '' || s == null ? null : Number(s));

function matches(d, f) {
  const t = f.text.trim().toLowerCase();
  if (t && !(d.issuerName.toLowerCase().includes(t) || d.dealId.toLowerCase().includes(t))) return false;
  if (f.role && d.ourRole !== f.role) return false;
  if (f.dealType && d.dealType !== f.dealType) return false;
  if (f.status && d.status !== f.status) return false;
  if (f.call && d.callProtection !== f.call) return false;
  if (f.rating && d.ratingSp !== f.rating) return false;
  const size = d.dealSizeUsd / 1e6;
  if (num(f.minSize) != null && size < num(f.minSize)) return false;
  if (num(f.maxSize) != null && size > num(f.maxSize)) return false;
  if (num(f.minYield) != null && !(d.yieldPct >= num(f.minYield))) return false;
  if (num(f.maxYield) != null && !(d.yieldPct <= num(f.maxYield))) return false;
  if (num(f.maxLev) != null && d.totalLeverageX > num(f.maxLev)) return false;
  if (f.from && !(d.pricedDate && d.pricedDate.slice(0, 10) >= f.from)) return false;
  if (f.to && !(d.pricedDate && d.pricedDate.slice(0, 10) <= f.to)) return false;
  return true;
}

function Pick({ label, value, onChange, options }) {
  return (
    <label className="ni-field"><span>{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Any</option>
        {options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
    </label>
  );
}
function Range({ label, lo, hi, onLo, onHi, unit, step = 'any', type = 'number' }) {
  return (
    <label className="ni-field"><span>{label}{unit ? ` (${unit})` : ''}</span>
      <div className="ni-range">
        <input type={type} step={step} placeholder="Min" value={lo} onChange={(e) => onLo(e.target.value)} />
        <input type={type} step={step} placeholder="Max" value={hi} onChange={(e) => onHi(e.target.value)} />
      </div>
    </label>
  );
}

export function NewIssueSearchPage() {
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const tab = tabOf(sp.get('tab'));
  const q = useApi(tab.market ? '/api/new-issue/deals?market=' + encodeURIComponent(tab.market) : null);
  const [f, setF] = useState(NO_FILTERS);
  const set = (k) => (v) => setF((s) => ({ ...s, [k]: v }));
  const deals = q.data?.deals || [];
  const opts = (key) => [...new Set(deals.map((d) => d[key]).filter(Boolean))].sort();
  const results = useMemo(() => deals.filter((d) => matches(d, f)), [deals, f]);
  const touched = JSON.stringify(f) !== JSON.stringify(NO_FILTERS);

  return (
    <>
      <TopBar title="Search Historical Deals" sub={`New Issue · ${tab.label}`}
        left={<button className="btn ghost small" onClick={() => nav('/new-issue?tab=' + tab.id)} title="Back to the calendar"><Icon name="left" size={13} /></button>} />
      <div className="content">
        <Tabs active={tab.id} onPick={(id) => nav('/new-issue/search?tab=' + id)}
          right={<button className="btn small" onClick={() => nav('/new-issue?tab=' + tab.id)}><Icon name="left" size={12} /> Back to calendar</button>} />
        {!tab.market ? <EmptyTab tab={tab} /> : q.loading && !q.data ? <Loading label="Loading deals" /> : q.error ? <ErrorBox error={q.error} /> : (
          <>
            <div className="ni-search-top">
              <div className="card ni-box">
                <h4>Product Search</h4>
                <input className="ni-input" placeholder="Issuer name or deal id" value={f.text} onChange={(e) => set('text')(e.target.value)} />
              </div>
              <div className="card ni-box">
                <h4>AI Powered Search <span className="tag">Coming soon</span></h4>
                <div className="ni-ai">
                  <input className="ni-input" disabled placeholder="How many deals greater than $500MM have priced YTD?" />
                  <button className="btn small" disabled><Icon name="spark" size={12} /> Ask</button>
                </div>
              </div>
            </div>
            <div className="ni-criteria">
              <div className="card ni-box">
                <h4>Search Criteria · Deal</h4>
                <Pick label="Our role" value={f.role} onChange={set('role')} options={['Lead left', 'Joint book', 'Co-manager', 'n/a']} />
                <Pick label="Deal type" value={f.dealType} onChange={set('dealType')} options={opts('dealType')} />
                <Pick label="Status" value={f.status} onChange={set('status')} options={['In Market', 'Priced']} />
              </div>
              <div className="card ni-box">
                <h4>Search Criteria · Size & pricing</h4>
                <Range label="Size" unit="$m" lo={f.minSize} hi={f.maxSize} onLo={set('minSize')} onHi={set('maxSize')} />
                <Range label="Yield" unit="%" lo={f.minYield} hi={f.maxYield} onLo={set('minYield')} onHi={set('maxYield')} />
                <Pick label="Call" value={f.call} onChange={set('call')} options={opts('callProtection')} />
              </div>
              <div className="card ni-box">
                <h4>Search Criteria · Credit & timing</h4>
                <Pick label="Issuer rating" value={f.rating} onChange={set('rating')} options={['BB+', 'BB', 'BB-', 'B+', 'B', 'B-', 'CCC+'].filter((r) => opts('ratingSp').includes(r))} />
                <label className="ni-field"><span>Max leverage (x)</span>
                  <input type="number" step="0.1" placeholder="Any" value={f.maxLev} onChange={(e) => set('maxLev')(e.target.value)} />
                </label>
                <Range label="Priced" type="date" lo={f.from} hi={f.to} onLo={set('from')} onHi={set('to')} />
              </div>
            </div>
            <div className="ni-caption">
              {results.length} of {deals.length} deals
              {touched && <button className="btn ghost small" onClick={() => setF(NO_FILTERS)}><Icon name="x" size={11} /> Clear</button>}
            </div>
            <DealTable deals={results} onOpen={(id) => nav('/new-issue/deals/' + id)} />
          </>
        )}
      </div>
    </>
  );
}

// ------------------------------------------------------------------ transaction details
const SECTIONS = ['Company Information', 'Deal Information', 'Tranche Details', 'Capital Structure', 'Covenants', 'Allocations', 'Fee Economics'];

function Facts({ rows }) {
  return (
    <dl className="ni-facts">
      {rows.filter(Boolean).map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v ?? '–'}</dd></div>
      ))}
    </dl>
  );
}

function Company({ issuer: i }) {
  if (!i) return <div className="empty">No company information.</div>;
  return (
    <>
      <p className="ni-lead">{i.description}</p>
      <Facts rows={[
        ['Industry', i.industry], ['Sector', i.sector],
        ['Headquarters', [i.hqCity, i.hqState, i.hqCountry].filter(Boolean).join(', ')],
        ['Ownership', i.ownership + (i.sponsor ? ` · ${i.sponsor}` : '') + (i.ticker ? ` · ${i.ticker}` : '')],
        ['Founded', i.foundedYear], ['Employees', i.employees?.toLocaleString('en-US')],
        ['LTM revenue', money(i.ltmRevenueUsd)], ['LTM EBITDA', money(i.ltmEbitdaUsd)], ['EBITDA margin', `${fix(i.ebitdaMarginPct, 1)}%`],
        ['Corporate rating', `${i.corporateRatingMoodys} / ${i.corporateRatingSp} · ${i.ratingOutlook}`],
        ['Bank client', i.clientId || 'No'], ['Website', i.website],
      ]} />
    </>
  );
}

function Deal({ deal: d }) {
  const live = d.status === 'In Market';
  return (
    <>
      <p className="ni-lead">{d.useOfProceeds}.</p>
      <Facts rows={[
        ['Status', d.status], ['Our role', d.ourRole], ['Deal type', d.dealType], ['Market', d.market],
        ['Launched', day(d.launchDate)],
        live ? ['Expected pricing', day(d.expectedPricingDate)] : ['Priced', day(d.pricedDate)],
        !live && ['Settlement', day(d.settlementDate)],
        ['Deal size', money(d.dealSizeUsd)], ['Tranches', d.trancheCount],
        live ? ['Price talk (lead)', `${talk(d.priceTalkLowPct, d.priceTalkHighPct)}%`] : ['Order book', `${money(d.orderbookUsd)} · ${x(d.oversubscriptionX)} covered`],
        ['Total leverage', x(d.totalLeverageX)], ['Secured leverage', x(d.securedLeverageX)], ['Net leverage', x(d.netLeverageX)],
        ['Issuer rating', `${d.ratingMoodys} / ${d.ratingSp}`],
      ]} />
      <h5>Syndicate</h5>
      <table className="table ni-sub">
        <thead><tr><th>Bank</th><th>Role</th></tr></thead>
        <tbody>{d.syndicate.map((s) => <tr key={s.bank} className={s.bank === 'Citizens' ? 'ni-us' : ''}><td>{s.bank}</td><td>{s.role}</td></tr>)}</tbody>
      </table>
    </>
  );
}

function Tranche({ t }) {
  if (!t) return <div className="empty">Pick a tranche above.</div>;
  const priced = t.couponPct != null;
  return (
    <>
      <p className="ni-lead">{t.securityDescription}</p>
      <div className="ni-two">
        <Facts rows={[
          ['Size', money(t.sizeUsd)], ['Seniority', `${t.seniority} · ${t.ranking}`],
          priced ? ['Coupon', `${fix(t.couponPct, 3)}%`] : ['Price talk', `${talk(t.priceTalkLowPct, t.priceTalkHighPct)}%`],
          priced && ['Issue price', fix(t.issuePrice, 3)], priced && ['Yield', `${fix(t.yieldPct, 2)}%`],
          priced && ['Spread', `+${t.spreadBps} bps vs ${t.benchmark}`], !priced && ['Benchmark', t.benchmark],
          priced && ['Order book', money(t.orderbookUsd)],
          ['Tenor', `${t.tenorYears} years · matures ${day(t.maturityDate)}`],
          ['Ratings', `${t.ratingMoodys} / ${t.ratingSp}`], ['Format', t.format], ['Min. denomination', money(t.minDenominationUsd)], ['CUSIP', t.cusip],
        ]} />
        <div>
          <h5>Call protection · {t.callProtection}</h5>
          <table className="table ni-sub">
            <thead><tr><th>From</th><th className="num">Call price</th></tr></thead>
            <tbody>{t.callSchedule.map((c) => <tr key={c.callDate}><td>{day(c.callDate)}</td><td className="num">{fix(c.callPrice, 3)}</td></tr>)}</tbody>
          </table>
          <Facts rows={[
            ['Equity clawback', `${t.equityClawbackPct}% at ${fix(t.equityClawbackPrice, 3)} before ${day(t.firstCallDate)}`],
            ['Change of control', `Put at ${t.changeOfControlPrice}`],
          ]} />
        </div>
      </div>
    </>
  );
}

function Capital({ rows }) {
  return (
    <table className="table ni-sub">
      <thead><tr><th>Instrument</th><th>Seniority</th><th className="num">Amount ($m)</th><th className="num">Maturity</th><th>Pricing</th><th className="num">Cum. leverage</th></tr></thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r._id} className={r.isNewIssue ? 'ni-us' : r.seniority === 'Cash' ? 'ni-total' : ''}>
            <td>{r.instrument}{r.isNewIssue && <span className="tag ni-new">New issue</span>}{r.committedUsd != null && <span className="ni-dim"> · {usdM(r.committedUsd)} committed</span>}</td>
            <td>{r.seniority === 'Cash' ? '' : r.seniority}</td>
            <td className="num">{r.amountUsd < 0 ? `(${usdM(-r.amountUsd)})` : usdM(r.amountUsd)}</td>
            <td className="num">{r.maturityYear ?? ''}</td>
            <td>{r.pricing ?? ''}</td>
            <td className="num">{r.seniority === 'Cash' ? `${x(r.cumulativeLeverageX)} net` : x(r.cumulativeLeverageX)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Covenants({ rows }) {
  return (
    <table className="table ni-sub">
      <thead><tr><th>Covenant</th><th>Test</th><th>IG fall-away</th></tr></thead>
      <tbody>{rows.map((c) => <tr key={c._id}><td>{c.name}</td><td>{c.test}</td><td>{c.suspendsOnInvestmentGrade ? 'Yes' : 'No'}</td></tr>)}</tbody>
    </table>
  );
}

function Allocations({ rows, t }) {
  if (!t) return <div className="empty">Pick a tranche above.</div>;
  const mine = rows.filter((a) => a.trancheId === t.trancheId);
  if (!mine.length) return <div className="empty">Allocations are set when the deal prices.</div>;
  return (
    <div className="ni-two">
      {['Investor type', 'Region'].map((dim) => (
        <div key={dim}>
          <h5>{t.trancheName} · by {dim.toLowerCase()}</h5>
          {mine.filter((a) => a.dimension === dim).map((a) => (
            <div key={a._id} className="ni-bar">
              <span>{a.category}</span>
              <div><i style={{ width: `${a.allocationPct}%` }} /></div>
              <b>{fix(a.allocationPct, 1)}%</b>
              <em>{usdM(a.allocationUsd)}m</em>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

function Fees({ fees: f }) {
  if (!f) return <div className="empty">No fee data.</div>;
  return (
    <>
      <Facts rows={[
        ['Status', f.feeStatus], ['Gross spread', `${fix(f.grossSpreadPct, 3)}%`], ['Gross fee', money(f.grossFeeUsd)],
        ['Management fee', `${f.managementFeePct}% · ${money(f.managementFeeUsd)}`],
        ['Underwriting fee', `${f.underwritingFeePct}% · ${money(f.underwritingFeeUsd)}`],
        ['Selling concession', `${f.sellingConcessionPct}% · ${money(f.sellingConcessionUsd)}`],
        ['Citizens role', f.citizensRole], ['Citizens fee', `${money(f.citizensFeeUsd)} · ${fix(f.citizensEconomicsPct, 1)}% of economics`],
      ]} />
      <h5>Economics by bank</h5>
      <table className="table ni-sub">
        <thead><tr><th>Bank</th><th>Role</th><th className="num">Economics (%)</th><th className="num">Fee</th></tr></thead>
        <tbody>{f.syndicate.map((s) => (
          <tr key={s.bank} className={s.bank === 'Citizens' ? 'ni-us' : ''}><td>{s.bank}</td><td>{s.role}</td><td className="num">{fix(s.economicsPct, 1)}</td><td className="num">{money(s.feeUsd)}</td></tr>
        ))}</tbody>
      </table>
    </>
  );
}

export function TransactionDetailsPage() {
  const { dealId } = useParams();
  const nav = useNavigate();
  const q = useApi('/api/new-issue/deals/' + dealId);
  const [section, setSection] = useState('Tranche Details');
  const [picked, setPicked] = useState(null);

  if (q.loading && !q.data) return <Loading label="Loading transaction" />;
  if (q.error) return <ErrorBox error={q.error} />;
  const { deal: d, issuer, tranches, capitalStructure, covenants, allocations, fees } = q.data;
  const t = tranches.find((r) => r.trancheId === picked) || tranches.find((r) => r.trancheId === d.leadTrancheId) || tranches[0];
  const live = d.status === 'In Market';

  return (
    <>
      <TopBar title={d.issuerName} sub={`Transaction details · ${d.market} · ${d.dealId} · ${live ? `in market, expected to price ${day(d.expectedPricingDate)}` : `priced ${day(d.pricedDate)}`}`}
        left={<button className="btn ghost small" onClick={() => nav(-1)} title="Back"><Icon name="left" size={13} /></button>} />
      <div className="content">
        <div className="ni-crumb">{d.issuerName}</div>
        <div className="ni-detail">
          <div className="card ni-side">
            <h4>Transaction Details</h4>
            {SECTIONS.map((s) => (
              <button key={s} className={'ni-side-item' + (s === section ? ' active' : '')} onClick={() => setSection(s)}>{s}</button>
            ))}
          </div>
          <div className="ni-main">
            <div className="ni-table-wrap">
              <table className="table ni-table">
                <thead>
                  <tr>
                    <th>Tranche Name</th><th>Our role</th><th>Call</th><th className="num">Priced</th><th className="num">Size ($m)</th>
                    <th className="num">Coupon (%)</th><th className="num">Yield (%)</th><th className="num">Leverage (x)</th>
                  </tr>
                </thead>
                <tbody>
                  {tranches.map((r) => (
                    <tr key={r.trancheId} className={'click' + (r.trancheId === t?.trancheId ? ' ni-picked' : '')}
                      onClick={() => { setPicked(r.trancheId); if (!['Tranche Details', 'Allocations'].includes(section)) setSection('Tranche Details'); }}>
                      <td>{r.trancheName}</td>
                      <td className={d.ourRole === 'n/a' ? 'ni-dim' : ''}>{d.ourRole}</td>
                      <td>{r.callProtection}</td>
                      <td className="num">{live ? <span className="ni-live">In market</span> : month(d.pricedDate)}</td>
                      <td className="num">{usdM(r.sizeUsd)}</td>
                      <td className="num">{r.couponPct != null ? fix(r.couponPct, 3) : <span className="ni-talk">{talk(r.priceTalkLowPct, r.priceTalkHighPct)}</span>}</td>
                      <td className="num">{fix(r.yieldPct, 2) === '–' ? '' : fix(r.yieldPct, 2)}</td>
                      <td className="num">{fix(d.totalLeverageX, 1)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="card ni-panel">
              <h4>{section}{['Tranche Details', 'Allocations'].includes(section) && t ? ` · ${t.trancheName}` : ''}</h4>
              {section === 'Company Information' && <Company issuer={issuer} />}
              {section === 'Deal Information' && <Deal deal={d} />}
              {section === 'Tranche Details' && <Tranche t={t} />}
              {section === 'Capital Structure' && <Capital rows={capitalStructure} />}
              {section === 'Covenants' && <Covenants rows={covenants} />}
              {section === 'Allocations' && <Allocations rows={allocations} t={t} />}
              {section === 'Fee Economics' && <Fees fees={fees} />}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
