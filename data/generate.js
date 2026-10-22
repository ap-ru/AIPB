// Generates the synthetic `bank` source database: a normalized commercial-banking system of record.
//
//   mongosh "mongodb://localhost:27017/bank" data/generate.js          (or: docker compose run --rm bank-seed)
//   BANK_RESET=true ...                                                regenerate even if data exists
//
// Every value is drawn from a seeded PRNG, so a run is reproducible; dates are relative to the run day (asOfDate in _meta).
// Hidden per-client drivers (stress, growth) keep the story consistent across collections: a stressed client has tighter
// covenants, higher utilization, more risk signals and a lower health score. The data dictionary lives in catalog.json
// and is loaded into `_catalog`; the self-checks at the end fail the run if the data and the dictionary disagree.

const fs = require('fs');
const path = require('path');

const GENERATOR_VERSION = 1;
const SEED = 42;
const SEED_DIR = process.env.SEED_DIR || (typeof __dirname !== 'undefined' ? __dirname : '/seed');
const RESET = process.env.BANK_RESET === 'true';
const bank = db.getSiblingDB(process.env.BANK_DB || 'bank');

const COLLECTIONS = ['products', 'clients', 'holdings', 'revenue_monthly', 'covenants', 'opportunities', 'signals', 'contacts', 'activities'];

if (bank.getCollection('_meta').findOne() && !RESET) {
  print(`bank already generated (asOfDate ${bank.getCollection('_meta').findOne().asOfDate.toISOString().slice(0, 10)}) - skipping. Set BANK_RESET=true to regenerate.`);
  quit(0);
}
[...COLLECTIONS, '_catalog', '_meta'].forEach((c) => bank.getCollection(c).drop());

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
const gauss = () => Math.sqrt(-2 * Math.log(1 - rand())) * Math.cos(2 * Math.PI * rand());
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const roundTo = (v, step) => Math.round(v / step) * step;
const r2 = (v) => Math.round(v * 100) / 100;
const weighted = (pairs) => { let r = rand() * pairs.reduce((s, p) => s + p[1], 0); for (const [v, w] of pairs) { if ((r -= w) <= 0) return v; } return pairs[pairs.length - 1][0]; };
const pad = (n, w) => String(n).padStart(w, '0');

const DAY = 864e5;
const today = new Date();
const AS_OF = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
const daysFromAsOf = (n) => new Date(AS_OF.getTime() + n * DAY);
const monthStart = (d) => new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1));
const monthsBack = (k) => new Date(Date.UTC(AS_OF.getUTCFullYear(), AS_OF.getUTCMonth() - k, 1));
const ym = (d) => d.toISOString().slice(0, 7);
const lastQuarterEnd = new Date(Date.UTC(AS_OF.getUTCFullYear(), Math.floor(AS_OF.getUTCMonth() / 3) * 3, 0));

// ------------------------------------------------------------------ reference data
const LOBS = ['Corporate Banking', 'Commercial Lending', 'Capital Markets', 'Global Markets'];

// productId, name, lob, productType, description
const PRODUCTS = [
  ['treasury-management', 'Treasury Management', 'Corporate Banking', 'treasury', 'Payments, collections, virtual accounts and cash reporting; earns monthly service fees.'],
  ['cash-pooling', 'Cash Pooling', 'Corporate Banking', 'treasury', 'Physical and notional pooling across entities; earns monthly service fees.'],
  ['commercial-card', 'Commercial Card', 'Corporate Banking', 'treasury', 'Purchasing and travel cards; earns interchange and program fees.'],
  ['trade-finance', 'Trade Finance', 'Corporate Banking', 'credit', 'Letters of credit and documentary collections, booked as a committed trade line.'],
  ['supply-chain-finance', 'Supply Chain Finance', 'Corporate Banking', 'credit', 'Payables finance program funding the client\'s suppliers.'],
  ['operating-deposits', 'Operating Deposits', 'Corporate Banking', 'deposit', 'Operating (transaction) account balances; earns net interest margin.'],
  ['liquidity-deposits', 'Liquidity Deposits', 'Corporate Banking', 'deposit', 'Term and money-market style liquidity balances; earns net interest margin.'],
  ['revolving-credit-facility', 'Revolving Credit Facility', 'Commercial Lending', 'credit', 'Committed revolver; drawn balance earns spread, undrawn earns a commitment fee.'],
  ['term-loan', 'Term Loan', 'Commercial Lending', 'credit', 'Amortizing term loan, usually close to fully drawn.'],
  ['cre-loan', 'CRE Loan', 'Commercial Lending', 'credit', 'Commercial real estate loan secured on property; carries LTV and DSCR covenants.'],
  ['equipment-finance', 'Equipment Finance', 'Commercial Lending', 'credit', 'Loans and leases secured on equipment.'],
  ['abl-facility', 'ABL Facility', 'Commercial Lending', 'credit', 'Asset-based revolver against receivables and inventory.'],
  ['acquisition-financing', 'Acquisition Financing', 'Commercial Lending', 'credit', 'Term debt funding an acquisition.'],
  ['syndicated-loan', 'Syndicated Loan', 'Capital Markets', 'credit', 'Our hold position in a loan arranged and syndicated by the bank.'],
  ['leveraged-finance', 'Leveraged Finance', 'Capital Markets', 'credit', 'Hold position in a leveraged (sub-investment grade) loan.'],
  ['dcm-bond-issuance', 'DCM Bond Issuance', 'Capital Markets', 'capital_markets', 'Bond deal underwritten for the client; one-off underwriting fee on completion.'],
  ['ecm-follow-on', 'ECM Follow-on', 'Capital Markets', 'capital_markets', 'Equity follow-on offering; one-off underwriting fee on completion.'],
  ['private-placement', 'Private Placement', 'Capital Markets', 'capital_markets', 'Privately placed notes; one-off placement fee on completion.'],
  ['ma-advisory', 'M&A Advisory', 'Capital Markets', 'capital_markets', 'Buy- or sell-side advisory mandate; success fee on completion.'],
  ['fx-hedging', 'FX Hedging Program', 'Global Markets', 'markets', 'FX forwards and options; earns trading revenue on notional hedged.'],
  ['interest-rate-swaps', 'Interest Rate Swaps', 'Global Markets', 'markets', 'Rate hedges on floating-rate debt; earns trading revenue on notional.'],
  ['commodity-hedging', 'Commodity Hedging', 'Global Markets', 'markets', 'Commodity swaps and options; earns trading revenue on notional.'],
  ['cross-currency-swap', 'Cross-Currency Swap', 'Global Markets', 'markets', 'Swaps of debt service between currencies; earns trading revenue on notional.'],
  ['structured-notes', 'Structured Notes', 'Global Markets', 'markets', 'Structured investment notes sold to the client\'s treasury; earns structuring revenue.'],
];
const PRODUCT = Object.fromEntries(PRODUCTS.map(([productId, name, lob, productType, description]) => [productId, { productId, name, lob, productType, description }]));
const productsOf = (lob) => PRODUCTS.filter((p) => p[2] === lob).map((p) => PRODUCT[p[0]]);

// code -> city, country, region
const MARKETS = {
  SFO: ['San Francisco', 'USA', 'AMER'], NYC: ['New York', 'USA', 'AMER'], AUS: ['Austin', 'USA', 'AMER'], LON: ['London', 'UK', 'EMEA'],
  TYO: ['Tokyo', 'Japan', 'APAC'], FRA: ['Frankfurt', 'Germany', 'EMEA'], SYD: ['Sydney', 'Australia', 'APAC'], PAR: ['Paris', 'France', 'EMEA'],
  TOR: ['Toronto', 'Canada', 'AMER'], CHI: ['Chicago', 'USA', 'AMER'], SIN: ['Singapore', 'Singapore', 'APAC'], HKG: ['Hong Kong', 'China', 'APAC'],
  DAL: ['Dallas', 'USA', 'AMER'], ZRH: ['Zurich', 'Switzerland', 'EMEA'], DXB: ['Dubai', 'UAE', 'EMEA'], SAO: ['Sao Paulo', 'Brazil', 'LATAM'],
  MEX: ['Mexico City', 'Mexico', 'LATAM'], MUM: ['Mumbai', 'India', 'APAC'], AMS: ['Amsterdam', 'Netherlands', 'EMEA'], BOG: ['Bogota', 'Colombia', 'LATAM'],
};

// name, industry, market - the same client universe as the app's demo book
const CLIENTS = [
  ['Vantage Energy Corp', 'Energy', 'SFO'], ['Northstar Communities', 'Real Estate', 'SFO'], ['Cobalt Semiconductors', 'Technology', 'SFO'],
  ['Quantum Data Centers', 'Technology', 'SFO'], ['Redwood Agritech', 'Agriculture', 'SFO'], ['Pacific Rim Ports', 'Transportation', 'SFO'],
  ['Gotham Payments', 'Financial Services', 'NYC'], ['Hudson Asset Holdings', 'Financial Services', 'NYC'], ['Orion Media Group', 'Media', 'NYC'],
  ['Crescent Pharma', 'Healthcare', 'NYC'], ['Liberty Freight', 'Transportation', 'NYC'], ['Lone Star Petrochem', 'Energy', 'AUS'],
  ['Desert Sun Solar', 'Energy', 'AUS'], ['Apex Construction', 'Industrials', 'AUS'], ['Nova Mobility', 'Automotive', 'AUS'],
  ['Thames Property Group', 'Real Estate', 'LON'], ['Beacon Insurance Group', 'Financial Services', 'LON'], ['Silverline Telecom', 'Telecom', 'LON'],
  ['Kansai Digital Grid', 'Utilities', 'TYO'], ['Sakura Robotics', 'Industrials', 'TYO'], ['Rheinland Energie AG', 'Utilities', 'FRA'],
  ['Alpine Precision AG', 'Industrials', 'FRA'], ['Sterling Chemicals', 'Materials', 'FRA'], ['Outback Resources', 'Mining', 'SYD'],
  ['Harbourline Logistics', 'Transportation', 'SYD'], ['Seine Luxe Group', 'Consumer', 'PAR'], ['Parisian Mobility SA', 'Automotive', 'PAR'],
  ['Maple Leaf Timber', 'Materials', 'TOR'], ['Northern Grid Utilities', 'Utilities', 'TOR'], ['Windy City Steel', 'Materials', 'CHI'],
  ['Atlas Manufacturing', 'Industrials', 'CHI'], ['Pinnacle Foods Group', 'Consumer', 'CHI'], ['Marina Bay Holdings', 'Real Estate', 'SIN'],
  ['Straits Shipping Co', 'Transportation', 'SIN'], ['Kowloon Trading Co', 'Consumer', 'HKG'], ['Jade Harbour Finance', 'Financial Services', 'HKG'],
  ['Titan Infrastructure', 'Industrials', 'DAL'], ['Falcon Defense Systems', 'Aerospace & Defense', 'DAL'], ['Helvetia Biotech AG', 'Healthcare', 'ZRH'],
  ['Gulf Horizon Hospitality', 'Hospitality', 'DXB'], ['Rio Grande Minerals', 'Mining', 'SAO'], ['Amazonia Agro SA', 'Agriculture', 'SAO'],
  ['Aztec Cement', 'Materials', 'MEX'], ['Bombay Textiles Ltd', 'Consumer', 'MUM'], ['Amstel Foods BV', 'Consumer', 'AMS'],
  ['Andes Coffee Exports', 'Agriculture', 'BOG'], ['Summit Aerospace', 'Aerospace & Defense', 'SFO'], ['Evergreen REIT', 'Real Estate', 'NYC'],
  ['Lumen Biotech', 'Healthcare', 'AUS'], ['Ironclad Industrial', 'Industrials', 'LON'], ['Horizon Hospitality', 'Hospitality', 'TYO'],
  ['Cascade Water Utilities', 'Utilities', 'SFO'], ['Keystone Materials', 'Materials', 'NYC'], ['Bluewater Shipping', 'Transportation', 'LON'],
  ['Aurora Retail Holdings', 'Consumer', 'TOR'], ['Granite Capital Partners', 'Financial Services', 'CHI'], ['Solace Renewables', 'Energy', 'FRA'],
  ['Meridian Logistics', 'Transportation', 'SYD'], ['Harborview Healthcare', 'Healthcare', 'SFO'], ['Bay State Medical', 'Healthcare', 'NYC'],
];

const BANKERS = ['Maya Chen', 'David Okafor', 'Priya Raman', 'James Whitfield', 'Sofia Alvarez',
  'Kenji Watanabe', 'Hannah Becker', 'Lucas Moreau', 'Aisha Rahman', 'Tom Brennan'];
const FIRST = ['Sarah', 'Michael', 'Elena', 'Robert', 'Grace', 'Daniel', 'Olivia', 'Arjun', 'Mei', 'Carlos', 'Ingrid', 'Yusuf', 'Nina', 'Peter', 'Amara', 'Lars'];
const LAST = ['Thompson', 'Nakamura', 'Rossi', 'Schmidt', 'Patel', 'Dubois', 'Kim', 'Silva', 'Novak', 'Hughes', 'Lindqvist', 'Adeyemi', 'Moreno', 'Byrne'];

const RATING_LABEL = (g) => (g <= 2 ? 'Strong' : g <= 4 ? 'Satisfactory' : g <= 6 ? 'Acceptable' : g === 7 ? 'Watch' : 'Substandard');
const EXTERNAL = ['AA', 'AA-', 'A', 'A-', 'BBB+', 'BBB', 'BBB-', 'BB+', 'BB'];

const OPEN_STAGES = ['Discovery', 'Proposal', 'Structuring', 'Credit Approval', 'Documentation', 'Closing'];
const STAGE_PROBABILITY = { Discovery: 10, Proposal: 25, Structuring: 40, 'Credit Approval': 60, Documentation: 80, Closing: 90, 'Closed Won': 100, 'Closed Lost': 0 };
const forecastCategory = (stage, p) => (stage.startsWith('Closed') ? 'Closed' : p >= 80 ? 'Commit' : p >= 40 ? 'Best Case' : 'Pipeline');
const NEXT_STEP = {
  Discovery: 'Schedule discovery session with the CFO', Proposal: 'Send term sheet and pricing indications',
  Structuring: 'Finalize structure and pricing grid with product partner', 'Credit Approval': 'Present to credit committee',
  Documentation: 'Turn legal comments on the facility agreement', Closing: 'Obtain signatures and fund',
};

function segmentFor(industry, i) {
  switch (industry) {
    case 'Financial Services': return 'Global Markets';
    case 'Real Estate': return 'Commercial Lending';
    case 'Technology': case 'Healthcare': return i % 2 === 0 ? 'Capital Markets' : 'Corporate Banking';
    case 'Energy': case 'Utilities': case 'Mining': return i % 3 === 0 ? 'Global Markets' : 'Commercial Lending';
    default: return LOBS[i % LOBS.length];
  }
}

// ------------------------------------------------------------------ clients
const clients = [];
const gen = {}; // hidden drivers per client - never written to the database

CLIENTS.forEach(([name, industry, market], i) => {
  const clientId = 'CL-' + pad(i + 1, 4);
  const [city, country, region] = MARKETS[market];
  let scale = between(0.4, 2.6);
  if (i < 2) scale = 2.3 - i * 0.4;
  const stress = i === 0 ? 0.1 : i === 1 ? 0.55 : clamp(rand() ** 1.6, 0, 1); // 0 = pristine, 1 = distressed
  const growth = clamp(0.06 + gauss() * 0.09 - stress * 0.12, -0.2, 0.3);  // annual revenue growth
  const grade = clamp(Math.round(3.2 + stress * 4 + gauss() * 1.0), 1, 9);
  const team = [
    { name: BANKERS[i % 10], role: 'Relationship Manager' },
    { name: BANKERS[(i + 3) % 10], role: 'Treasury Officer' },
    { name: BANKERS[(i + 5) % 10], role: 'Credit Officer' },
    { name: BANKERS[(i + 7) % 10], role: 'Markets Specialist' },
  ];
  const kycRoll = rand();
  const since = new Date(Date.UTC(AS_OF.getUTCFullYear() - int(2, 26), int(0, 11), int(1, 28)));
  clients.push({
    _id: clientId, clientId, name, industry,
    segment: segmentFor(industry, i),
    tier: i < 2 || scale > 1.7 ? 'Strategic' : scale > 0.9 ? 'Core' : 'Growth',
    city, country, region,
    relationshipManager: team[0].name,
    coverageTeam: team,
    internalRatingGrade: grade,
    internalRatingLabel: RATING_LABEL(grade),
    externalRating: EXTERNAL[Math.min(EXTERNAL.length - 1, grade - 1)],
    healthScore: null, // computed after covenants and signals exist
    esgScore: clamp(Math.round(62 + gauss() * 14), 20, 96),
    annualRevenueUsd: roundTo(scale * between(800, 4800) * 1e6, 1e6),
    employees: Math.round(scale * between(1500, 22000)),
    walletSizeUsd: null, // computed from revenue
    kycStatus: kycRoll < 0.1 + stress * 0.08 ? 'Overdue' : kycRoll < 0.32 ? 'Refresh Due' : 'Current',
    nextReviewDate: daysFromAsOf(int(-20, 220)),
    relationshipSince: since,
  });
  gen[clientId] = { scale, stress, growth, team };
});

// ------------------------------------------------------------------ holdings
const holdings = [];
let holdingSeq = 1;

function addHolding(client, product) {
  const { scale, stress } = gen[client.clientId];
  const h = {
    _id: 'HL-' + pad(holdingSeq, 5), holdingId: 'HL-' + pad(holdingSeq++, 5), clientId: client.clientId,
    productId: product.productId, productName: product.name, lob: product.lob, productType: product.productType,
  };
  const sinceDays = Math.round((AS_OF - client.relationshipSince) / DAY);
  const recent = chance(0.15);
  let opened = daysFromAsOf(-int(recent ? 20 : 730, recent ? 700 : Math.max(800, Math.min(sinceDays, 4500))));
  if (opened < client.relationshipSince) opened = client.relationshipSince;
  const pending = product.productType !== 'capital_markets' && chance(0.05);
  if (pending) opened = daysFromAsOf(-int(3, 30));
  h.status = pending ? 'Pending' : 'Active';

  switch (product.productType) {
    case 'credit': {
      const committed = roundTo(scale * between(25, 320) * 1e6, 1e6);
      const termLike = ['term-loan', 'cre-loan', 'acquisition-financing', 'equipment-finance', 'syndicated-loan', 'leveraged-finance'].includes(product.productId);
      const util = pending ? 0 : termLike ? between(0.82, 1) : clamp(between(0.2, 0.75) + stress * 0.3, 0, 0.99);
      h.committedUsd = committed;
      h.drawnUsd = roundTo(committed * util, 1e5);
      let maturity = new Date(opened.getTime() + int(3, 7) * 365 * DAY);
      if (maturity <= AS_OF) maturity = daysFromAsOf(int(20, 1500));
      h.maturityDate = maturity;
      break;
    }
    case 'deposit':
      h.balanceUsd = pending ? 0 : roundTo(scale * between(15, 220) * 1e6 * (1 - stress * 0.3), 1e5);
      break;
    case 'markets': {
      h.notionalUsd = roundTo(scale * between(20, 400) * 1e6, 1e6);
      let maturity = new Date(opened.getTime() + int(1, 5) * 365 * DAY);
      if (maturity <= AS_OF) maturity = daysFromAsOf(int(15, 1200));
      h.maturityDate = maturity;
      break;
    }
    case 'capital_markets':
      // a completed mandate: openedDate is the completion date, inside the 24-month revenue window
      opened = monthsBack(int(1, 23));
      opened = new Date(opened.getTime() + int(0, 27) * DAY);
      h.notionalUsd = roundTo(scale * between(150, 900) * 1e6, 1e6);
      h.status = 'Completed';
      break;
    default: // treasury - fee-only, no balance
      break;
  }
  h.openedDate = opened;
  if (h.status === 'Active' && h.maturityDate && h.maturityDate - AS_OF < 180 * DAY) h.status = 'Maturing';
  holdings.push(h);
  return h;
}

clients.forEach((c) => {
  const held = new Set();
  const add = (p) => { if (!held.has(p.productId)) { held.add(p.productId); addHolding(c, p); } };
  add(PRODUCT['operating-deposits']);
  if (chance(0.85)) add(PRODUCT['treasury-management']);
  LOBS.forEach((lob) => {
    const n = lob === c.segment ? int(2, 3) : chance(0.5) ? 1 : 0;
    for (let k = 0; k < n; k++) add(pick(productsOf(lob)));
  });
  // every commercial client borrows: a revolver if nothing else
  if (![...held].some((id) => PRODUCT[id].productType === 'credit')) add(PRODUCT['revolving-credit-facility']);
});

// ------------------------------------------------------------------ revenue_monthly (24 closed months, newest = last month)
const revenue = [];
const CM_FEE = { 'dcm-bond-issuance': [0.005, 0.009], 'ecm-follow-on': [0.02, 0.03], 'private-placement': [0.007, 0.012], 'ma-advisory': [0.006, 0.012] };

holdings.forEach((h) => {
  if (h.status === 'Pending') return;
  const { scale, growth } = gen[h.clientId];
  const spread = between(0.012, 0.03);
  const margin = between(0.006, 0.016);
  const tmFee = scale * between(15e3, 90e3);
  const mktRate = between(0.0006, 0.002);
  const opened = monthStart(h.openedDate);
  for (let k = 24; k >= 1; k--) {
    const month = monthsBack(k);
    if (month < opened) continue;
    const g = Math.pow(1 + growth, -(k - 1) / 12);
    const noise = 1 + gauss() * 0.04;
    let nii = 0;
    let fee = 0;
    switch (h.productType) {
      case 'credit':
        nii = h.drawnUsd * g * noise * spread / 12;
        fee = (h.committedUsd - h.drawnUsd) * g * 0.0035 / 12;
        if (month.getTime() === opened.getTime()) fee += h.committedUsd * 0.005; // upfront arrangement fee
        break;
      case 'deposit':
        nii = h.balanceUsd * g * noise * margin / 12;
        fee = between(500, 3000);
        break;
      case 'treasury':
        fee = tmFee * g * noise;
        break;
      case 'markets':
        fee = Math.max(0, h.notionalUsd * mktRate / 12 * g * (1 + gauss() * 0.3));
        break;
      case 'capital_markets':
        if (month.getTime() === opened.getTime()) fee = h.notionalUsd * between(...CM_FEE[h.productId]);
        break;
    }
    nii = Math.round(nii);
    fee = Math.round(fee);
    if (nii + fee === 0) continue;
    revenue.push({
      _id: `${h.holdingId}-${ym(month)}`, clientId: h.clientId, holdingId: h.holdingId, productId: h.productId, lob: h.lob,
      month, niiUsd: nii, feeUsd: fee, totalUsd: nii + fee,
    });
  }
});

// wallet = trailing-12-month revenue / a hidden share of wallet
const t12 = {};
revenue.filter((r) => r.month >= monthsBack(12)).forEach((r) => { t12[r.clientId] = (t12[r.clientId] || 0) + r.totalUsd; });
clients.forEach((c) => {
  const sow = between(0.18, 0.65);
  c.walletSizeUsd = roundTo(Math.max(t12[c.clientId] || 0, 1e6) / sow, 1e5);
});

// ------------------------------------------------------------------ covenants
const covenants = [];
let covSeq = 1;
const COVENANT_TEMPLATES = {
  leverage: { name: 'Max Total Leverage', metric: 'Total Debt / EBITDA', operator: '<=', unit: 'x', threshold: () => r2(between(3.5, 4.5)) },
  interest: { name: 'Min Interest Coverage', metric: 'EBITDA / Interest Expense', operator: '>=', unit: 'x', threshold: () => r2(between(2.5, 3.0)) },
  fixedCharge: { name: 'Min Fixed Charge Coverage', metric: '(EBITDA - Capex) / Fixed Charges', operator: '>=', unit: 'x', threshold: () => r2(between(1.15, 1.25)) },
  liquidity: { name: 'Min Liquidity', metric: 'Cash + Undrawn Committed Facilities', operator: '>=', unit: 'USD', threshold: (s) => roundTo(s * between(25, 90) * 1e6, 5e6) },
  ltv: { name: 'Max Loan-to-Value', metric: 'Loan Balance / Appraised Property Value', operator: '<=', unit: '%', threshold: () => int(65, 75) },
  dscr: { name: 'Min Debt Service Coverage', metric: 'Net Operating Income / Debt Service', operator: '>=', unit: 'x', threshold: () => r2(between(1.2, 1.35)) },
};
const headroomOf = (op, threshold, actual) => r2(op === '<=' ? (threshold - actual) / threshold * 100 : (actual - threshold) / threshold * 100);
const covenantStatus = (headroom) => (headroom < 0 ? 'Breach' : headroom < 15 ? 'Watch' : 'Compliant');

holdings.filter((h) => h.productType === 'credit' && h.status !== 'Pending' && ['Commercial Lending', 'Capital Markets'].includes(h.lob)).forEach((h) => {
  const { scale, stress } = gen[h.clientId];
  const keys = h.productId === 'cre-loan' ? ['ltv', 'dscr']
    : ['leverage', 'interest', 'fixedCharge', 'liquidity'].filter(() => chance(0.55)).slice(0, 3);
  if (keys.length === 0) keys.push('leverage');
  keys.forEach((key) => {
    const t = COVENANT_TEMPLATES[key];
    const threshold = t.threshold(scale);
    const target = weighted([[between(-15, -1), 0.02 + stress * 0.12], [between(1, 14.5), 0.08 + stress * 0.22], [between(15, 65), 1]]);
    let actual = t.operator === '<=' ? threshold * (1 - target / 100) : threshold * (1 + target / 100);
    actual = t.unit === 'USD' ? roundTo(actual, 1e5) : r2(actual);
    const headroomPct = headroomOf(t.operator, threshold, actual);
    covenants.push({
      _id: 'CV-' + pad(covSeq, 4), covenantId: 'CV-' + pad(covSeq++, 4), clientId: h.clientId, holdingId: h.holdingId,
      name: t.name, metric: t.metric, operator: t.operator, threshold, actual, unit: t.unit, headroomPct,
      status: covenantStatus(headroomPct), testFrequency: 'Quarterly', lastTestDate: lastQuarterEnd,
    });
  });
});

// ------------------------------------------------------------------ opportunities
const opportunities = [];
let oppSeq = 1;
const shortName = (c) => c.name.split(' ')[0];

clients.forEach((c) => {
  const { scale, team } = gen[c.clientId];
  const heldIds = new Set(holdings.filter((h) => h.clientId === c.clientId).map((h) => h.productId));
  const notHeld = PRODUCTS.map((p) => PRODUCT[p[0]]).filter((p) => !heldIds.has(p.productId) && p.productType !== 'deposit');
  const held = [...heldIds].map((id) => PRODUCT[id]);
  const nOpen = int(2, 5) + (c.tier === 'Strategic' ? 2 : 0);
  const nClosed = int(2, 5);
  for (let k = 0; k < nOpen + nClosed; k++) {
    const isOpen = k < nOpen;
    const product = chance(0.55) && notHeld.length ? pick(notHeld) : pick(held);
    const stage = isOpen ? weighted([['Discovery', 3], ['Proposal', 3], ['Structuring', 2.5], ['Credit Approval', 2], ['Documentation', 1.5], ['Closing', 1]])
      : chance(0.62) ? 'Closed Won' : 'Closed Lost';
    const probability = STAGE_PROBABILITY[stage];
    let dealSize;
    let expected;
    switch (product.productType) {
      case 'credit': dealSize = roundTo(scale * between(20, 300) * 1e6, 1e6); expected = dealSize * (between(0.012, 0.03) + 0.005); break;
      case 'deposit': dealSize = roundTo(scale * between(20, 150) * 1e6, 1e6); expected = dealSize * 0.01; break;
      case 'treasury': dealSize = roundTo(scale * between(0.2, 1.2) * 1e6, 1e4); expected = dealSize; break;
      case 'markets': dealSize = roundTo(scale * between(25, 400) * 1e6, 1e6); expected = dealSize * between(0.001, 0.002); break;
      default: dealSize = roundTo(scale * between(150, 900) * 1e6, 1e6); expected = dealSize * between(...CM_FEE[product.productId]);
    }
    const closeDate = isOpen ? daysFromAsOf(int(-45, 200)) : daysFromAsOf(-int(5, 365));
    const slippedBefore = (isOpen && closeDate < AS_OF) || chance(0.2);
    const originalCloseDate = slippedBefore ? new Date(closeDate.getTime() - int(15, 90) * DAY) : closeDate;
    const daysInStage = int(1, 95);
    const createdDate = new Date(Math.min(originalCloseDate.getTime(), closeDate.getTime()) - int(45, 240) * DAY);
    const ownerRole = { credit: 'Credit Officer', treasury: 'Treasury Officer', deposit: 'Treasury Officer', markets: 'Markets Specialist' }[product.productType] || 'Relationship Manager';
    const blockers = [];
    if (isOpen && OPEN_STAGES.indexOf(stage) >= 2 && chance(0.4)) {
      const areas = c.kycStatus === 'Overdue' ? ['KYC'] : [];
      while (areas.length < int(1, 2)) { const a = pick(['Credit', 'Legal', 'KYC', 'Pricing', 'Procurement']); if (!areas.includes(a)) areas.push(a); }
      areas.forEach((area) => blockers.push({ area, status: area === 'KYC' && c.kycStatus === 'Overdue' ? 'Blocked' : pick(['Blocked', 'In Progress', 'In Progress', 'Not Started', 'Cleared']) }));
    }
    const o = {
      _id: 'OP-' + pad(oppSeq, 5), opportunityId: 'OP-' + pad(oppSeq++, 5), clientId: c.clientId,
      name: `${shortName(c)} - ${product.name}${heldIds.has(product.productId) ? ' (renewal / upsize)' : ''}`,
      productId: product.productId, productName: product.name, lob: product.lob, stage, probability,
      forecastCategory: forecastCategory(stage, probability),
      dealSizeUsd: dealSize, expectedRevenueUsd: roundTo(expected, 1e3),
      closeDate, originalCloseDate, createdDate,
      owner: (team.find((t) => t.role === ownerRole) || team[0]).name,
      riskLevel: weighted([['Low', 5], ['Medium', 3 + gen[c.clientId].stress * 4], ['High', 1 + gen[c.clientId].stress * 5]]),
      daysInStage: isOpen ? daysInStage : 0,
      nextStep: isOpen ? NEXT_STEP[stage] : null,
      blockers,
    };
    if (!isOpen) delete o.nextStep;
    opportunities.push(o);
  }
});

// ------------------------------------------------------------------ signals
const signals = [];
let sigSeq = 1;
const money = (v) => (Math.abs(v) >= 1e9 ? `$${(v / 1e9).toFixed(2)}B` : Math.abs(v) >= 1e6 ? `$${(v / 1e6).toFixed(1)}M` : `$${Math.round(v / 1e3)}K`);
const fmtCov = (v, unit) => (unit === 'USD' ? money(v) : unit === '%' ? v.toFixed(1) + '%' : v.toFixed(2) + 'x');

function addSignal(c, s) {
  const ageMin = Math.round(Math.pow(rand(), 2) * 45 * 24 * 60);
  const detectedAt = new Date(AS_OF.getTime() + 12 * 3600e3 - ageMin * 60e3);
  const ageDays = ageMin / 1440;
  const stage = weighted([['New', ageDays < 3 ? 5 : 1], ['Triaged', 3], ['Engaged', 2.5], ['Actioned', 1.5], ['Resolved', ageDays > 10 ? 3 : 0.3]]);
  signals.push({
    _id: 'SG-' + pad(sigSeq, 4), signalId: 'SG-' + pad(sigSeq++, 4), clientId: c.clientId,
    category: s.category, type: s.type, severity: s.severity, title: s.title, summary: s.summary, source: s.source,
    detectedAt, stage, confidence: s.confidence || int(55, 92), impactUsd: roundTo(s.impactUsd, 1e3),
    owner: c.relationshipManager,
    ...(s.holdingId ? { holdingId: s.holdingId } : {}),
    ...(s.covenantId ? { covenantId: s.covenantId } : {}),
    ...(s.opportunityId ? { opportunityId: s.opportunityId } : {}),
    evidence: s.evidence, recommendedActions: s.actions,
  });
}

const RANDOM_SIGNALS = [
  ['Risk', 'Liquidity', 'P1', 'Operating account outflows up 42% week over week', 'Payments flows', 'Outgoing payments from operating accounts spiked week over week, concentrated in three new counterparties.',
    ['Call the Treasurer to understand cash usage', 'Check for vendor concentration in outflows', 'Review revolver availability'], 'risk'],
  ['Risk', 'Rating', 'P2', 'External rating outlook revised to Negative', 'News', 'A rating agency moved the outlook to Negative, citing weaker margins and higher leverage.',
    ['Refresh the internal rating model', 'Review pricing grid step-ups', 'Update the watchlist committee'], 'risk'],
  ['Risk', 'Deposit Attrition', 'P2', 'Deposit balances down 18% in 30 days', 'Payments flows', 'Operating balances fell steadily over the last month with no matching drawdown on our facilities.',
    ['Benchmark deposit pricing against competitors', 'Offer a liquidity sweep structure', 'Schedule a relationship check-in'], 'risk'],
  ['Risk', 'Management Change', 'P2', 'CFO departure announced', 'News', 'The company announced the departure of its CFO effective next month.',
    ['Map the new finance leadership', 'Send an introduction from the senior coverage banker', 'Reconfirm pending approvals'], 'risk'],
  ['Opportunity', 'M&A', 'P1', 'Acquisition announced - external financing need likely', 'News', 'A strategic acquisition was announced; public filings imply an external financing need and no bank mandate is announced yet.',
    ['Pitch bridge-to-bond financing', 'Engage the leveraged finance desk', 'Offer acquisition FX hedging'], 'opportunity'],
  ['Opportunity', 'Excess Liquidity', 'P3', 'Idle balances building in operating accounts', 'Payments flows', 'Balances above operating needs have built up for 60+ days.',
    ['Propose a money market sweep', 'Introduce structured deposit options', 'Review cash forecasting'], 'opportunity'],
  ['Opportunity', 'Capex', 'P3', 'Capex plan announced - equipment finance opportunity', 'News', 'Management guided to a step-up in capital expenditure over the next two years.',
    ['Offer an equipment finance line', 'Bundle with trade finance for imports', 'Align with the vendor finance team'], 'opportunity'],
  ['Service', 'Escalation', 'P1', 'Wire failures on the treasury platform', 'Service desk', 'Multiple failed high-value wires in the last 24 hours; Tier 3 payments operations are engaged.',
    ['Engage Tier 3 payments operations', 'Send a proactive update to the Treasurer', 'Run a post-incident review within 48h'], 'service'],
  ['Service', 'CSAT', 'P2', 'CSAT dropped 14 points after lockbox migration', 'Service desk', 'Satisfaction scores fell sharply after the lockbox migration went live.',
    ['Schedule a service recovery call', 'Assign a dedicated implementation manager', 'Track the remediation plan'], 'service'],
  ['Market', 'Sector Stress', 'P2', 'Sector stress: peer margins compressing', 'Market data', 'Peers in the same industry reported margin compression and widening credit spreads this quarter.',
    ['Re-underwrite exposures in the sector', 'Stress test coverage ratios at +200bps', 'Review concentration limits'], 'risk'],
  ['Market', 'Commodity', 'P3', 'Input commodity prices up 8% in a week', 'Market data', 'A sharp move in a key input commodity raises hedging demand.',
    ['Reach out on commodity hedging', 'Share desk commentary', 'Review margin requirements'], 'opportunity'],
];

clients.forEach((c) => {
  const { stress } = gen[c.clientId];
  const ch = holdings.filter((h) => h.clientId === c.clientId);
  const exposure = ch.filter((h) => h.productType === 'credit').reduce((s, h) => s + h.drawnUsd, 0);

  covenants.filter((cv) => cv.clientId === c.clientId && cv.status !== 'Compliant').forEach((cv) => {
    if (cv.status === 'Watch' && !chance(0.7)) return;
    const h = ch.find((x) => x.holdingId === cv.holdingId);
    addSignal(c, {
      category: 'Risk', type: 'Covenant', severity: cv.status === 'Breach' ? 'P1' : 'P2', source: 'Credit monitoring', confidence: int(90, 99),
      title: cv.status === 'Breach' ? `${cv.name} covenant breached on ${h.productName}` : `${cv.name} headroom down to ${cv.headroomPct.toFixed(1)}%`,
      summary: `${cv.metric} tested at ${fmtCov(cv.actual, cv.unit)} against a ${cv.operator} ${fmtCov(cv.threshold, cv.unit)} covenant at the last quarterly test.`,
      impactUsd: h.drawnUsd, holdingId: h.holdingId, covenantId: cv.covenantId,
      evidence: [{ label: 'Actual', value: fmtCov(cv.actual, cv.unit) }, { label: 'Threshold', value: `${cv.operator} ${fmtCov(cv.threshold, cv.unit)}` },
        { label: 'Headroom', value: cv.headroomPct.toFixed(1) + '%' }, { label: 'Drawn on facility', value: money(h.drawnUsd) }],
      actions: cv.status === 'Breach' ? ['Notify the credit officer and freeze new draws', 'Request an updated compliance certificate', 'Model amendment and waiver pricing']
        : ['Request management accounts for the current quarter', 'Model amendment and waiver pricing', 'Brief the credit officer before the next draw'],
    });
  });

  ch.filter((h) => h.productType === 'credit' && h.drawnUsd / h.committedUsd > 0.9 && !['term-loan', 'cre-loan', 'acquisition-financing', 'equipment-finance', 'syndicated-loan', 'leveraged-finance'].includes(h.productId)).forEach((h) => {
    const u = h.drawnUsd / h.committedUsd;
    addSignal(c, {
      category: 'Risk', type: 'Utilization', severity: 'P2', source: 'Credit monitoring', confidence: int(88, 98),
      title: `${h.productName} utilization at ${Math.round(u * 100)}%`,
      summary: `Drawn balance is ${money(h.drawnUsd)} of a ${money(h.committedUsd)} commitment, leaving little headroom for working capital swings.`,
      impactUsd: h.drawnUsd, holdingId: h.holdingId,
      evidence: [{ label: 'Drawn', value: money(h.drawnUsd) }, { label: 'Committed', value: money(h.committedUsd) }, { label: 'Utilization', value: Math.round(u * 100) + '%' }],
      actions: ['Ask the Treasurer for a 13-week cash forecast', 'Discuss a facility upsize or ABL structure', 'Check borrowing base and covenant headroom'],
    });
  });

  ch.filter((h) => h.status === 'Maturing' && h.productType === 'credit').forEach((h) => {
    const days = Math.round((h.maturityDate - AS_OF) / DAY);
    const opp = opportunities.find((o) => o.clientId === c.clientId && o.productId === h.productId && !o.stage.startsWith('Closed'));
    addSignal(c, {
      category: 'Opportunity', type: 'Refinancing', severity: 'P2', source: 'Credit monitoring', confidence: int(92, 99),
      title: `${money(h.committedUsd)} ${h.productName} matures in ${days} days`,
      summary: `The facility matures on ${h.maturityDate.toISOString().slice(0, 10)}. Refinancing should start now to avoid a competitor approach.`,
      impactUsd: h.committedUsd * 0.02, holdingId: h.holdingId, ...(opp ? { opportunityId: opp.opportunityId } : {}),
      evidence: [{ label: 'Committed', value: money(h.committedUsd) }, { label: 'Maturity', value: h.maturityDate.toISOString().slice(0, 10) }],
      actions: ['Prepare refinancing options', 'Run DCM and loan market pricing indications', 'Offer a pre-hedge on rates'],
    });
  });

  if (c.kycStatus === 'Overdue') {
    addSignal(c, {
      category: 'Service', type: 'KYC', severity: 'P2', source: 'CRM', confidence: 99,
      title: 'KYC refresh overdue - new business blocked',
      summary: 'The periodic KYC refresh is past due. New facilities and products cannot be booked until it is cleared.',
      impactUsd: opportunities.filter((o) => o.clientId === c.clientId && !o.stage.startsWith('Closed')).reduce((s, o) => s + o.expectedRevenueUsd, 0),
      evidence: [{ label: 'KYC status', value: 'Overdue' }, { label: 'Next review', value: c.nextReviewDate.toISOString().slice(0, 10) }],
      actions: ['Request outstanding KYC documents', 'Escalate to the onboarding lead', 'Hold new facilities until cleared'],
    });
  }

  if (!ch.some((h) => h.productType === 'markets') && c.annualRevenueUsd > 2e9 && chance(0.5)) {
    addSignal(c, {
      category: 'Opportunity', type: 'FX Exposure', severity: 'P2', source: 'Payments flows',
      title: 'Foreign-currency receivables growing with no hedges booked',
      summary: 'Inbound foreign-currency collections rose sharply this quarter while no forward cover is booked with us.',
      impactUsd: c.annualRevenueUsd * 0.0004,
      evidence: [{ label: 'Client revenue', value: money(c.annualRevenueUsd) }, { label: 'Hedges with us', value: 'None' }],
      actions: ['Propose a layered FX forward program', 'Share a hedge-effectiveness analysis', 'Set up FX e-trading access'],
    });
  }

  const nRandom = chance(0.55 + stress * 0.3) ? int(1, 2) : 0;
  for (let k = 0; k < nRandom; k++) {
    const pool = RANDOM_SIGNALS.filter((t) => (stress > 0.5 ? true : t[7] !== 'risk' || chance(0.4)));
    const [category, type, severity, title, source, summary, actions, kind] = pick(pool);
    const opp = category === 'Opportunity' ? opportunities.find((o) => o.clientId === c.clientId && !o.stage.startsWith('Closed')) : null;
    addSignal(c, {
      category, type, severity, title, source, summary, actions,
      impactUsd: kind === 'risk' ? exposure * between(0.05, 0.2) : between(0.3, 5) * 1e6 * (severity === 'P1' ? 2 : 1),
      ...(opp ? { opportunityId: opp.opportunityId } : {}),
      evidence: [{ label: 'Total exposure', value: money(exposure) }, { label: 'Detected by', value: source }],
    });
  }
});

// ------------------------------------------------------------------ health score
clients.forEach((c) => {
  const { stress, growth } = gen[c.clientId];
  const cv = covenants.filter((x) => x.clientId === c.clientId);
  const p1Risk = signals.filter((s) => s.clientId === c.clientId && s.category === 'Risk' && s.severity === 'P1' && s.stage !== 'Resolved').length;
  let h = 86 - stress * 28 + gauss() * 6;
  h -= 12 * cv.filter((x) => x.status === 'Breach').length + 4 * cv.filter((x) => x.status === 'Watch').length;
  h -= 6 * p1Risk + (c.kycStatus === 'Overdue' ? 5 : 0) + (growth < 0 ? 4 : 0);
  c.healthScore = clamp(Math.round(h), 15, 98);
});

// ------------------------------------------------------------------ contacts + activities
const contacts = [];
const activities = [];
let contactSeq = 1;
let actSeq = 1;
const CONTACT_ROLES = [
  ['Chief Financial Officer', 'Decision Maker'], ['Treasurer', 'Treasury'], ['Controller', 'Finance'],
  ['Chief Executive Officer', 'Decision Maker'], ['VP Corporate Development', 'Influencer'], ['Head of Procurement', 'Influencer'],
];
// type, summary, owner role, contact title (null = internal)
const ACTIVITY_TEMPLATES = [
  ['Meeting', 'Quarterly business review - discussed capex plan and FX exposure', 'Relationship Manager', 'Chief Financial Officer'],
  ['Call', 'Treasury walkthrough of real-time payments and virtual accounts', 'Treasury Officer', 'Treasurer'],
  ['Credit Review', 'Annual credit review completed - internal rating affirmed', 'Credit Officer', null],
  ['Email', 'Sent refinancing indications for the maturing facility', 'Relationship Manager', 'Chief Financial Officer'],
  ['Site Visit', 'Site visit with the operations team - supply chain finance pilot scoped', 'Relationship Manager', 'Controller'],
  ['Call', 'Hedging strategy discussion ahead of the rate decision', 'Markets Specialist', 'Treasurer'],
  ['Meeting', 'Executive sponsor dinner - strategic priorities for next year', 'Relationship Manager', 'Chief Executive Officer'],
  ['Email', 'Shared deposit pricing proposal', 'Treasury Officer', 'Treasurer'],
  ['Call', 'Acquisition pipeline discussion with corporate development', 'Relationship Manager', 'VP Corporate Development'],
];

clients.forEach((c) => {
  const { team } = gen[c.clientId];
  const domain = c.name.toLowerCase().replace(/\b(corp|co|group|holdings|ag|sa|bv|ltd|inc)\b/g, '').replace(/[^a-z]+/g, '') + '.com';
  const roles = [CONTACT_ROLES[0], CONTACT_ROLES[1], ...CONTACT_ROLES.slice(2).filter(() => chance(0.5))];
  const used = new Set();
  const mine = roles.map(([title, role], k) => {
    let first; let last;
    do { first = pick(FIRST); last = pick(LAST); } while (used.has(first + last));
    used.add(first + last);
    const ct = {
      _id: 'CT-' + pad(contactSeq, 5), contactId: 'CT-' + pad(contactSeq++, 5), clientId: c.clientId,
      name: `${first} ${last}`, title, role, email: `${first}.${last}@${domain}`.toLowerCase(), isPrimary: k === 0, lastContactDate: null,
    };
    contacts.push(ct);
    return ct;
  });

  const n = int(5, 11);
  for (let k = 0; k < n; k++) {
    const [type, summary, ownerRole, contactTitle] = pick(ACTIVITY_TEMPLATES);
    const contact = contactTitle ? (mine.find((x) => x.title === contactTitle) || mine[0]) : null;
    const date = daysFromAsOf(-int(0, 180));
    activities.push({
      _id: 'AC-' + pad(actSeq, 5), activityId: 'AC-' + pad(actSeq++, 5), clientId: c.clientId, date, type, summary,
      owner: team.find((t) => t.role === ownerRole).name, ...(contact ? { contactId: contact.contactId } : {}),
    });
    if (contact && (!contact.lastContactDate || date > contact.lastContactDate)) contact.lastContactDate = date;
  }
  mine.forEach((ct) => { if (!ct.lastContactDate) ct.lastContactDate = daysFromAsOf(-int(181, 400)); });
});

// ------------------------------------------------------------------ write
const docs = {
  products: PRODUCTS.map(([productId]) => ({ _id: productId, ...PRODUCT[productId] })),
  clients, holdings, revenue_monthly: revenue, covenants, opportunities, signals, contacts, activities,
};
COLLECTIONS.forEach((c) => bank.getCollection(c).insertMany(docs[c], { ordered: true }));
['holdings', 'revenue_monthly', 'covenants', 'opportunities', 'signals', 'contacts', 'activities'].forEach((c) => bank.getCollection(c).createIndex({ clientId: 1 }));
bank.revenue_monthly.createIndex({ month: 1 });
bank.revenue_monthly.createIndex({ holdingId: 1, month: 1 });
bank.opportunities.createIndex({ stage: 1 });
bank.signals.createIndex({ detectedAt: -1 });

const catalog = JSON.parse(fs.readFileSync(path.join(SEED_DIR, 'catalog.json'), 'utf8'));
bank.getCollection('_catalog').insertMany(catalog);

const counts = Object.fromEntries(COLLECTIONS.map((c) => [c, bank.getCollection(c).countDocuments()]));
bank.getCollection('_meta').insertOne({ _id: 'bank', asOfDate: AS_OF, generatorVersion: GENERATOR_VERSION, seed: SEED, generatedAt: new Date(), counts });

// ------------------------------------------------------------------ self-checks (read back from the database)
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
const all = (c) => bank.getCollection(c).find().toArray();
const ids = (c, key) => new Set(all(c).map((d) => d[key]));
const clientIds = ids('clients', 'clientId');
const holdingIds = ids('holdings', 'holdingId');
const productIds = ids('products', 'productId');
const oppIds = ids('opportunities', 'opportunityId');
const covIds = ids('covenants', 'covenantId');
const contactIds = ids('contacts', 'contactId');

COLLECTIONS.filter((c) => !['products', 'clients'].includes(c)).forEach((c) => {
  const bad = all(c).filter((d) => !clientIds.has(d.clientId)).length;
  check(bad === 0, `${c}: ${bad} rows with an unknown clientId`);
});
check(all('holdings').every((h) => productIds.has(h.productId)), 'holdings: unknown productId');
check(all('opportunities').every((o) => productIds.has(o.productId)), 'opportunities: unknown productId');
check(all('revenue_monthly').every((r) => holdingIds.has(r.holdingId)), 'revenue_monthly: unknown holdingId');
check(all('revenue_monthly').every((r) => r.totalUsd === r.niiUsd + r.feeUsd && r.month < monthStart(AS_OF)), 'revenue_monthly: total != nii + fee, or month not closed');
check(all('holdings').filter((h) => h.productType === 'credit').every((h) => h.drawnUsd <= h.committedUsd), 'holdings: drawnUsd > committedUsd');
all('covenants').forEach((cv) => {
  check(holdingIds.has(cv.holdingId), `covenants ${cv.covenantId}: unknown holdingId`);
  check(Math.abs(headroomOf(cv.operator, cv.threshold, cv.actual) - cv.headroomPct) < 0.011, `covenants ${cv.covenantId}: headroomPct does not match threshold/actual`);
  check(covenantStatus(cv.headroomPct) === cv.status, `covenants ${cv.covenantId}: status does not match headroom`);
});
all('opportunities').forEach((o) => {
  check(STAGE_PROBABILITY[o.stage] === o.probability, `opportunities ${o.opportunityId}: probability does not match stage`);
  check(forecastCategory(o.stage, o.probability) === o.forecastCategory, `opportunities ${o.opportunityId}: forecastCategory does not match probability`);
});
all('signals').forEach((s) => {
  if (s.holdingId) check(holdingIds.has(s.holdingId), `signals ${s.signalId}: unknown holdingId`);
  if (s.covenantId) check(covIds.has(s.covenantId), `signals ${s.signalId}: unknown covenantId`);
  if (s.opportunityId) check(oppIds.has(s.opportunityId), `signals ${s.signalId}: unknown opportunityId`);
});
check(all('activities').every((a) => !a.contactId || contactIds.has(a.contactId)), 'activities: unknown contactId');
check(all('clients').every((c) => c.healthScore !== null && c.walletSizeUsd > 0), 'clients: healthScore or walletSizeUsd missing');

// the dictionary must describe exactly the fields that exist
catalog.filter((e) => COLLECTIONS.includes(e._id)).forEach((e) => {
  const documented = new Set(e.fields.map((f) => f.name));
  const observed = new Set(bank.getCollection(e._id).aggregate([
    { $project: { kv: { $objectToArray: '$$ROOT' } } }, { $unwind: '$kv' }, { $group: { _id: '$kv.k' } },
  ]).toArray().map((d) => d._id));
  observed.forEach((f) => check(documented.has(f), `catalog: ${e._id}.${f} exists in the data but is not documented`));
  documented.forEach((f) => check(observed.has(f), `catalog: ${e._id}.${f} is documented but never appears in the data`));
});
COLLECTIONS.forEach((c) => check(catalog.some((e) => e._id === c), `catalog: no entry for ${c}`));

print(`bank generated as of ${AS_OF.toISOString().slice(0, 10)}: ` + Object.entries(counts).map(([c, n]) => `${c}=${n}`).join(', ') + `, _catalog=${catalog.length}`);
if (failures.length) {
  print(`SELF-CHECK FAILED (${failures.length}):\n  ` + failures.slice(0, 40).join('\n  '));
  quit(1);
}
print('all self-checks passed');
