// Generates the pre-call brief data the original precall_* seed does not have: upcoming meetings, a 24-month loan and
// deposit balance history, the last 30 days of payment flows, ownership, and an industry context per industry. It
// writes only these five collections and reads clients, holdings, contacts, opportunities and precall_company_profiles
// - every other collection in `bank` is left as it is.
//
//   mongosh "mongodb://localhost:27017/bank" data/generate-precall-plus.js      (or: docker compose run --rm precall-plus-seed)
//   PRECALL_PLUS_RESET=true ...                                                regenerate even if the data exists
//
// Synthetic. Values are derived from the existing client book so the brief agrees with Client 360: the balance history
// ends exactly at today's drawn loans and deposit balances, and payment sizes scale with the client's deposits. Every
// value is drawn from a seeded PRNG; dates are relative to the bank's asOfDate (_meta). The dictionary is
// catalog-precall-plus.json and the self-checks at the end fail the run if the data and the dictionary disagree.

const fs = require('fs');
const path = require('path');

const GENERATOR_VERSION = 1;
const SEED = 7331;
const SEED_DIR = process.env.SEED_DIR || (typeof __dirname !== 'undefined' ? __dirname : '/seed');
const RESET = process.env.PRECALL_PLUS_RESET === 'true';
const bank = db.getSiblingDB(process.env.BANK_DB || 'bank');

const COLLECTIONS = ['precall_upcoming_meetings', 'precall_balance_history', 'precall_payment_flows', 'precall_ownership', 'precall_industry_context'];

const meta = bank.getCollection('_meta').findOne({ _id: 'bank' });
if (!meta || !bank.precall_company_profiles.findOne()) { print('bank / precall_* is not generated yet - run generate.js first.'); quit(1); }
if (bank.getCollection('precall_balance_history').findOne() && !RESET) {
  print('precall plus already generated - skipping. Set PRECALL_PLUS_RESET=true to regenerate.');
  quit(0);
}
COLLECTIONS.forEach((c) => bank.getCollection(c).drop());

// ------------------------------------------------------------------ random + date helpers
let state = SEED >>> 0;
function rand() { // mulberry32
  state = (state + 0x6D2B79F5) | 0;
  let t = Math.imul(state ^ (state >>> 15), 1 | state);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const between = (a, b) => a + rand() * (b - a);
const int = (a, b) => Math.floor(between(a, b + 1));
const pick = (a) => a[Math.floor(rand() * a.length)];
const chance = (p) => rand() < p;
const roundTo = (v, step) => Math.round(v / step) * step;
const r1 = (v) => Math.round(v * 10) / 10;
const weighted = (pairs) => { let r = rand() * pairs.reduce((s, p) => s + p[1], 0); for (const [v, w] of pairs) { if ((r -= w) <= 0) return v; } return pairs[pairs.length - 1][0]; };
const pad = (n, w) => String(n).padStart(w, '0');
const shuffle = (a) => { const b = a.slice(); for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; } return b; };
const num = (v) => (v == null ? 0 : Number(v));

const DAY = 864e5;
const AS_OF = meta.asOfDate;
const daysFromAsOf = (n) => new Date(AS_OF.getTime() + n * DAY);
const monthEnd = (k) => new Date(Date.UTC(AS_OF.getUTCFullYear(), AS_OF.getUTCMonth() - k + 1, 0)); // k=1: last month's end

// ------------------------------------------------------------------ reference data
const INDUSTRY = {
  Energy: ['Stable', 'Upstream cash flows remain solid at current oil and gas prices; capital discipline keeps leverage low, while midstream volumes grow with LNG export demand.',
    ['Oil and gas prices', 'LNG export capacity', 'Capex discipline'], ['Commodity price volatility', 'Energy transition policy', 'Regulatory permitting delays'], 2.5, 28, 1.6],
  'Real Estate': ['Negative', 'Office valuations are still resetting and refinancing walls are heavy in 2026-27; industrial and multifamily hold up better.',
    ['Interest rate path', 'Occupancy and rent growth', 'Refinancing availability'], ['Office vacancy', 'Maturity wall', 'Cap rate expansion'], -1.5, 52, 3.4],
  Technology: ['Positive', 'Enterprise software and semiconductor demand stay strong on AI infrastructure spend; valuations support equity-funded M&A.',
    ['AI and data-center capex', 'Enterprise IT budgets', 'Chip supply cycles'], ['Export controls', 'Customer concentration', 'Talent costs'], 9.0, 24, 1.1],
  Agriculture: ['Stable', 'Crop prices have normalised from 2022 highs; input costs eased, keeping farm margins steady but below peak.',
    ['Crop prices', 'Fertiliser and input costs', 'Export demand'], ['Weather events', 'Trade tariffs', 'Land price softening'], 1.0, 14, 2.0],
  Transportation: ['Stable', 'Freight volumes are recovering after the 2024-25 downturn; contract rates firm as excess capacity exits.',
    ['Freight volumes', 'Fuel prices', 'Contract rate resets'], ['Overcapacity', 'Labour costs', 'Fuel price spikes'], 3.0, 16, 2.6],
  'Financial Services': ['Stable', 'Net interest margins are past their peak; fee businesses benefit from higher capital-markets activity.',
    ['Rate path', 'Capital markets activity', 'Credit quality'], ['Credit losses', 'Regulatory capital changes', 'Deposit competition'], 4.0, 30, 0.9],
  Media: ['Negative', 'Linear TV advertising and subscribers keep declining; streaming profitability is improving but scale is uneven.',
    ['Advertising market', 'Streaming subscribers', 'Content costs'], ['Cord-cutting', 'Ad market cyclicality', 'High leverage at legacy players'], -2.0, 18, 3.8],
  Healthcare: ['Stable', 'Utilisation is high and pricing steady; labour costs are easing but reimbursement pressure persists.',
    ['Patient volumes', 'Reimbursement rates', 'Labour availability'], ['Drug pricing reform', 'Staffing costs', 'Payer mix shifts'], 5.0, 20, 2.2],
  Industrials: ['Stable', 'Order books are healthy on reshoring and infrastructure spending; destocking has run its course.',
    ['Infrastructure spending', 'Reshoring capex', 'PMI new orders'], ['Tariffs on inputs', 'Labour shortages', 'Cyclical slowdown'], 4.5, 17, 1.9],
  Utilities: ['Positive', 'Load growth from data centers and electrification supports rate-base growth; heavy capex needs funding.',
    ['Rate-base growth', 'Data-center load', 'Regulatory outcomes'], ['Wildfire liability', 'Large capex funding', 'Rate case delays'], 5.5, 35, 0.6],
  Materials: ['Stable', 'Chemical and packaging volumes are stabilising after destocking; margins recover slowly with lower input costs.',
    ['Industrial production', 'Feedstock costs', 'Pricing power'], ['Overcapacity from imports', 'Energy costs', 'Demand softness'], 2.0, 19, 2.4],
  Mining: ['Positive', 'Copper and critical-mineral demand from electrification keeps prices firm; supply growth is constrained.',
    ['Copper and metal prices', 'Electrification demand', 'Supply discipline'], ['Commodity price swings', 'Permitting', 'Jurisdiction risk'], 6.0, 33, 2.1],
  Consumer: ['Stable', 'Volumes are flat as consumers trade down; price increases have slowed and promotions are rising.',
    ['Real wage growth', 'Pricing vs volume', 'Input cost inflation'], ['Private-label share gains', 'Retailer destocking', 'Promotional intensity'], 2.0, 15, 2.3],
  Automotive: ['Negative', 'EV transition costs and tariffs pressure margins; suppliers face volume uncertainty and pricing pressure from OEMs.',
    ['Vehicle production', 'EV adoption pace', 'Tariff policy'], ['OEM pricing pressure', 'EV program delays', 'Tariffs on parts'], -0.5, 11, 3.1],
  Telecom: ['Stable', 'Wireless growth is slowing; fiber build-outs continue with high capex and stable cash flows.',
    ['Subscriber growth', 'Fiber build-out', 'ARPU trends'], ['Capex intensity', 'Price competition', 'Leverage'], 1.5, 36, 1.8],
  'Aerospace & Defense': ['Positive', 'Commercial aircraft backlogs are at records and defense budgets are rising; supply chains remain the bottleneck.',
    ['Aircraft deliveries', 'Defense budgets', 'Aftermarket demand'], ['Supply-chain bottlenecks', 'Program execution', 'Budget politics'], 7.0, 16, 1.0],
  Hospitality: ['Stable', 'Leisure travel has normalised and business travel is still recovering; RevPAR growth is modest.',
    ['RevPAR', 'Business travel', 'Group bookings'], ['Consumer slowdown', 'Labour costs', 'Refinancing costs'], 3.0, 22, 2.8],
};
const INSTITUTIONS = ['Northgate Asset Management', 'Bluecrest Investors', 'Sterling Point Advisors', 'Harborview Capital', 'Evergreen Index Funds',
  'Pioneer Pension Trust', 'Lakeside Global Equity', 'Summit Ridge Partners', 'Crescent Bay Investments', 'Granite Peak Advisors'];
const SPONSORS = ['Ridgeline Capital Partners', 'Blue Harbor Equity', 'Kestrel Partners', 'Ironwood Capital', 'Summitview Partners', 'Larkspur Equity'];
const CUSTOMERS = ['Midwest Distribution Co', 'Apex Retail Group', 'Sunbelt Wholesale', 'Pacific Supply Partners', 'Keystone Logistics', 'Northern Utilities Co',
  'Coastal Grocers', 'Prime Industrial Supply', 'Metro Health System', 'Atlas Contracting'];
const SUPPLIERS = ['Continental Steel Works', 'Global Freight Lines', 'Premier Packaging', 'Delta Components', 'Sierra Chemicals', 'Highway Fuel Services',
  'Union Electric Supply', 'Cobalt Software Inc', 'Westfield Equipment Rental', 'Ironbridge Engineering'];
const MEETING_PURPOSE = [
  ['Quarterly relationship review', ['Business update and outlook', 'Review of credit facilities and usage', 'Open opportunities and next steps']],
  ['Annual credit review', ['Financial results and covenant compliance', 'Rating discussion', 'Facility renewal terms']],
  ['Treasury optimisation session', ['Liquidity and cash concentration', 'Payment flows and fraud controls', 'Investment of excess balances']],
  ['Financing discussion', ['Capital needs for the next 12 months', 'Debt market conditions', 'Structure and pricing indications']],
  ['Hedging strategy review', ['FX and rate exposures', 'Current hedge program', 'Proposed hedge ratios']],
];

// ------------------------------------------------------------------ inputs (read only)
const clients = bank.clients.find().sort({ clientId: 1 }).toArray();
const profileOf = Object.fromEntries(bank.precall_company_profiles.find().toArray().map((p) => [p.clientId, p]));
const holdingsOf = {};
bank.holdings.find().forEach((h) => (holdingsOf[h.clientId] ||= []).push(h));
const contactsOf = {};
bank.contacts.find().forEach((c) => (contactsOf[c.clientId] ||= []).push(c));
const openOppsOf = {};
bank.opportunities.find({ stage: { $nin: ['Closed Won', 'Closed Lost'] } }).forEach((o) => (openOppsOf[o.clientId] ||= []).push(o));
const currentBalances = (cid) => {
  const hs = holdingsOf[cid] || [];
  return {
    loan: hs.filter((h) => h.productType === 'credit').reduce((s, h) => s + num(h.drawnUsd), 0),
    commit: hs.filter((h) => h.productType === 'credit').reduce((s, h) => s + num(h.committedUsd), 0),
    deposit: hs.filter((h) => h.productType === 'deposit').reduce((s, h) => s + num(h.balanceUsd), 0),
  };
};

// ------------------------------------------------------------------ build
const docs = Object.fromEntries(COLLECTIONS.map((c) => [c, []]));

clients.forEach((c) => {
  const p = profileOf[c.clientId];
  const base = { clientId: c.clientId, sourcePrId: p.sourcePrId };
  const id = (tag, n) => `${p._id}-${tag}-${pad(n, 4)}`;
  const team = c.coverageTeam || [];
  const rm = (team.find((t) => t.role === 'Relationship Manager') || team[0]).name;
  const contacts = contactsOf[c.clientId] || [];
  const opps = openOppsOf[c.clientId] || [];
  const cur = currentBalances(c.clientId);

  // ---- balance history: 23 month-ends plus today, walked back from today's balances
  const loanTrend = between(-0.012, 0.02);   // monthly drift, applied backwards
  const depTrend = between(-0.015, 0.015);
  let loan = cur.loan, dep = cur.deposit, commit = cur.commit;
  const rows = [];
  for (let k = 0; k < 24; k++) {
    rows.push({ asOfDate: k === 0 ? AS_OF : monthEnd(k), loan, commit, dep });
    loan = Math.max(0, roundTo(loan / (1 + loanTrend + between(-0.03, 0.03)), 1e5));
    dep = Math.max(0, roundTo(dep / (1 + depTrend + between(-0.05, 0.05)), 1e5));
    if (chance(0.06) && commit > 0) commit = roundTo(commit * between(0.8, 0.95), 1e6); // a facility upsized since then
    loan = Math.min(loan, commit);
  }
  rows.reverse().forEach((r, k) => docs.precall_balance_history.push({
    _id: id('BAL', k + 1), ...base, asOfDate: r.asOfDate, isCurrent: k === 23,
    loanBalanceUsd: r.loan, loanCommitmentUsd: r.commit, depositBalanceUsd: r.dep,
  }));

  // ---- payment flows: last 30 days, incoming and outgoing, sized to the client's deposits and revenue
  const scale = Math.max(cur.deposit, num(c.annualRevenueUsd) / 40, 2e6);
  const nPay = int(18, 34);
  const customers = shuffle(CUSTOMERS).slice(0, 5);
  const suppliers = shuffle(SUPPLIERS).slice(0, 5);
  for (let k = 0; k < nPay; k++) {
    const incoming = chance(0.5);
    const [cpType, cp] = incoming
      ? weighted([[['Customer', pick(customers)], 8], [['Intercompany', `${c.name} Holdings`], 1], [['Investor', pick(INSTITUTIONS)], 0.4]])
      : weighted([[['Supplier', pick(suppliers)], 6], [['Payroll', 'Payroll'], 2], [['Tax authority', 'US Treasury - IRS'], 0.6],
        [['Lender', 'Loan interest and principal'], 0.8], [['Intercompany', `${c.name} Holdings`], 0.6]]);
    const big = chance(0.15);
    const amount = roundTo(scale * (big ? between(0.08, 0.25) : between(0.004, 0.05)), 1000);
    const foreign = c.region !== 'AMER' && chance(0.35);
    docs.precall_payment_flows.push({
      _id: id('PAY', k + 1), ...base, paymentDate: daysFromAsOf(-int(0, 29)), direction: incoming ? 'Incoming' : 'Outgoing',
      counterparty: cp, counterpartyType: cpType,
      channel: cpType === 'Payroll' ? 'ACH' : cpType === 'Tax authority' ? 'Wire' : weighted([['Wire', 5], ['ACH', 4], ['RTP', 1], ['Book transfer', 1]]),
      currency: foreign ? pick(['EUR', 'GBP', 'JPY', 'BRL', 'AUD']) : 'USD', amountUsd: amount,
    });
  }

  // ---- upcoming meetings: one to three in the next 45 days
  const nMeet = weighted([[1, 5], [2, 3], [3, 1]]);
  const days = shuffle(Array.from({ length: 44 }, (_, i) => i + 2)).slice(0, nMeet).sort((a, b) => a - b);
  days.forEach((d, k) => {
    const [purpose, agenda] = k === 0 && opps.length ? ['Financing discussion', MEETING_PURPOSE[3][1]] : pick(MEETING_PURPOSE);
    const opp = purpose === 'Financing discussion' && opps.length ? pick(opps) : null;
    const theirs = shuffle(contacts).slice(0, int(1, Math.min(3, contacts.length)));
    const ours = [rm, ...shuffle(team.filter((t) => t.name !== rm)).slice(0, int(0, 2)).map((t) => t.name)];
    const type = weighted([['In person', 5], ['Video', 4], ['Call', 1]]);
    const at = daysFromAsOf(d);
    at.setUTCHours(pick([14, 15, 16, 18, 19, 20]), pick([0, 30]));
    const doc = {
      _id: id('MTG', k + 1), ...base, meetingDate: at, durationMinutes: pick([30, 45, 60, 90]), meetingType: type,
      location: type === 'In person' ? `${c.name}, ${c.city}` : type === 'Video' ? 'Video conference' : 'Phone',
      purpose, agenda: opp ? [...agenda, `${opp.productName}: ${opp.nextStep || 'next steps'}`] : agenda,
      citizensAttendees: ours, clientAttendees: theirs.map((t) => `${t.name} (${t.title})`), organizer: rm, status: 'Scheduled',
    };
    if (opp) doc.opportunityId = opp.opportunityId;
    docs.precall_upcoming_meetings.push(doc);
  });

  // ---- ownership: public holders and float, a sponsor, a founding family, or a parent
  const kind = p.privatePublicIndicator;
  const holders = [];
  if (kind === 'Public') {
    const inst = shuffle(INSTITUTIONS).slice(0, int(3, 5));
    let left = between(40, 70);
    inst.forEach((name, k) => {
      const pct = k === inst.length - 1 ? left * between(0.3, 0.6) : left * between(0.15, 0.35);
      left -= pct;
      holders.push(['Institutional', name, r1(pct)]);
    });
    holders.push(['Insider', 'Directors and officers', r1(between(1, 8))]);
    holders.push(['Public float', 'Other public shareholders', 0]);
  } else {
    const shape = weighted([['Private equity', 5], ['Family', 4], ['Parent company', 2]]);
    if (shape === 'Private equity') {
      holders.push(['Private equity', pick(SPONSORS), r1(between(55, 85))]);
      holders.push(['Insider', 'Management', r1(between(5, 15))]);
      holders.push(['Private equity', pick(SPONSORS.filter((s) => s !== holders[0][1])), 0]);
    } else if (shape === 'Family') {
      const family = c.name.split(' ')[0] + ' family';
      holders.push(['Family', family, r1(between(60, 90))]);
      holders.push(['Employees', 'Employee ownership plan', 0]);
    } else {
      holders.push(['Parent company', `${c.name.split(' ')[0]} Group Holdings`, 100]);
    }
  }
  const named = holders.reduce((s, h) => s + h[2], 0);
  const last = holders[holders.length - 1];
  if (last[2] === 0) last[2] = r1(100 - named);
  holders.forEach(([type, name, pct], k) => docs.precall_ownership.push({
    _id: id('OWN', k + 1), ...base, holderName: name, holderType: type, ownershipPct: pct,
    isControlling: pct >= 50, asOfDate: monthEnd(1),
  }));
});

// ---- industry context: one row per industry the clients are in
[...new Set(clients.map((c) => c.industry))].sort().forEach((ind) => {
  const [outlook, summary, drivers, risks, growth, margin, defaultRate] = INDUSTRY[ind] || ['Stable', `${ind}: no outlook on file.`, [], [], 0, 0, 0];
  docs.precall_industry_context.push({
    _id: ind, industry: ind, outlook, summary, keyDrivers: drivers, keyRisks: risks,
    revenueGrowthPct: growth, ebitdaMarginPct: margin, defaultRatePct: defaultRate, asOfDate: monthEnd(1), source: 'Citizens industry research (synthetic)',
  });
});

// ------------------------------------------------------------------ write
COLLECTIONS.forEach((c) => bank.getCollection(c).insertMany(docs[c], { ordered: true }));
bank.precall_upcoming_meetings.createIndex({ clientId: 1, meetingDate: 1 });
bank.precall_balance_history.createIndex({ clientId: 1, asOfDate: 1 });
bank.precall_payment_flows.createIndex({ clientId: 1, direction: 1, amountUsd: -1 });
bank.precall_ownership.createIndex({ clientId: 1 });

// ------------------------------------------------------------------ self-checks (read back from the database)
const catalog = JSON.parse(fs.readFileSync(path.join(SEED_DIR, 'catalog-precall-plus.json'), 'utf8'));
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
const all = (c) => bank.getCollection(c).find().toArray();
const clientIds = new Set(clients.map((c) => c.clientId));

COLLECTIONS.filter((c) => c !== 'precall_industry_context').forEach((c) => {
  const rows = all(c);
  check(rows.every((d) => clientIds.has(d.clientId)), `${c}: unknown clientId`);
  check(rows.every((d) => profileOf[d.clientId] && d._id.startsWith(profileOf[d.clientId]._id + '-')), `${c}: _id without the client's profile prefix`);
  check(new Set(rows.map((d) => d.clientId)).size === clients.length, `${c}: not every client has rows`);
});
clients.forEach((c) => {
  const cur = currentBalances(c.clientId);
  const hist = bank.precall_balance_history.find({ clientId: c.clientId }).sort({ asOfDate: 1 }).toArray();
  const now = hist[hist.length - 1];
  check(hist.length === 24 && now.isCurrent && hist.filter((h) => h.isCurrent).length === 1, `precall_balance_history ${c.clientId}: 24 rows, last one current`);
  check(now.loanBalanceUsd === cur.loan && now.depositBalanceUsd === cur.deposit && now.loanCommitmentUsd === cur.commit,
    `precall_balance_history ${c.clientId}: current row does not match holdings`);
  check(hist.every((h) => h.loanBalanceUsd <= h.loanCommitmentUsd || h.loanCommitmentUsd === 0), `precall_balance_history ${c.clientId}: loan above commitment`);
  const own = bank.precall_ownership.find({ clientId: c.clientId }).toArray();
  check(Math.abs(own.reduce((s, o) => s + o.ownershipPct, 0) - 100) < 0.05, `precall_ownership ${c.clientId}: does not sum to 100%`);
  check(bank.precall_industry_context.findOne({ _id: c.industry }) !== null, `precall_industry_context: no row for ${c.industry}`);
});
check(all('precall_payment_flows').every((p) => p.paymentDate <= AS_OF && p.paymentDate > daysFromAsOf(-30) && p.amountUsd > 0), 'precall_payment_flows: outside the last 30 days or not positive');
check(all('precall_upcoming_meetings').every((m) => m.meetingDate > AS_OF), 'precall_upcoming_meetings: a meeting is not in the future');

COLLECTIONS.forEach((c) => {
  const e = catalog.find((x) => x._id === c);
  check(!!e, `catalog-precall-plus: no entry for ${c}`);
  if (!e) return;
  const documented = new Set(e.fields.map((f) => f.name));
  const observed = new Set(bank.getCollection(c).aggregate([
    { $project: { kv: { $objectToArray: '$$ROOT' } } }, { $unwind: '$kv' }, { $group: { _id: '$kv.k' } },
  ]).toArray().map((d) => d._id));
  observed.forEach((f) => check(documented.has(f), `catalog-precall-plus: ${c}.${f} exists in the data but is not documented`));
  documented.forEach((f) => check(observed.has(f), `catalog-precall-plus: ${c}.${f} is documented but never appears in the data`));
});

print(`precall plus generated (v${GENERATOR_VERSION}) as of ${AS_OF.toISOString().slice(0, 10)}: ` + COLLECTIONS.map((c) => `${c}=${bank.getCollection(c).countDocuments()}`).join(', '));
if (failures.length) {
  print(`SELF-CHECK FAILED (${failures.length}):\n  ` + failures.slice(0, 40).join('\n  '));
  quit(1);
}
print('all self-checks passed');
