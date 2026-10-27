// Generates the High Yield new-issue data behind the CM&A workspace's New Issue page: the in-market calendar, priced
// deals, and each deal's transaction details (company, deal, tranches, capital structure, covenants, allocations and
// fee economics). It writes only the hy_* collections and reads `clients` and `_meta` - every other collection in
// `bank` is left as it is.
//
//   mongosh "mongodb://localhost:27017/bank" data/generate-hy.js      (or: docker compose run --rm hy-seed)
//   HY_RESET=true ...                                                 regenerate even if the hy_* data exists
//
// Synthetic. Issuers are dummy companies; the three BB-rated bank clients also issue, linked by clientId. Every value
// is drawn from a seeded PRNG; dates are relative to the bank's asOfDate (_meta). The dictionary is catalog-hy.json and
// the self-checks at the end fail the run if the data and the dictionary disagree.

const fs = require('fs');
const path = require('path');

const GENERATOR_VERSION = 1;
const SEED = 4242;
const SEED_DIR = process.env.SEED_DIR || (typeof __dirname !== 'undefined' ? __dirname : '/seed');
const RESET = process.env.HY_RESET === 'true';
const bank = db.getSiblingDB(process.env.BANK_DB || 'bank');

const COLLECTIONS = ['hy_issuers', 'hy_deals', 'hy_tranches', 'hy_capital_structure', 'hy_covenants', 'hy_allocations', 'hy_fee_economics'];

const meta = bank.getCollection('_meta').findOne({ _id: 'bank' });
if (!meta) { print('bank is not generated yet - run generate.js first.'); quit(1); }
if (bank.getCollection('hy_deals').findOne() && !RESET) {
  print('hy_* already generated - skipping. Set HY_RESET=true to regenerate.');
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
const r2 = (v) => Math.round(v * 100) / 100;
const r3 = (v) => Math.round(v * 1000) / 1000;
const weighted = (pairs) => { let r = rand() * pairs.reduce((s, p) => s + p[1], 0); for (const [v, w] of pairs) { if ((r -= w) <= 0) return v; } return pairs[pairs.length - 1][0]; };
const pad = (n, w) => String(n).padStart(w, '0');
const shuffle = (a) => { const b = a.slice(); for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; } return b; };

const DAY = 864e5;
const AS_OF = meta.asOfDate;
const daysFromAsOf = (n) => new Date(AS_OF.getTime() + n * DAY);
const addYears = (d, y) => new Date(Date.UTC(d.getUTCFullYear() + y, d.getUTCMonth(), d.getUTCDate()));
const M = 1e6;

// ------------------------------------------------------------------ reference data
const SP = ['BB+', 'BB', 'BB-', 'B+', 'B', 'B-', 'CCC+'];
const MOODYS = ['Ba1', 'Ba2', 'Ba3', 'B1', 'B2', 'B3', 'Caa1'];
const notch = (i, by) => Math.max(0, Math.min(SP.length - 1, i + by));
// by corporate rating index: leverage range, spread (bps) for secured and unsecured notes, gross spread (% of size)
const LEVERAGE = [[2.6, 3.4], [3.0, 3.8], [3.4, 4.3], [3.9, 4.8], [4.4, 5.4], [5.0, 6.2], [6.0, 7.4]];
const SPREAD_SECURED = [[160, 210], [185, 240], [215, 280], [260, 340], [310, 400], [370, 470], [480, 600]];
const SPREAD_UNSECURED = [[200, 255], [230, 290], [265, 335], [320, 410], [390, 490], [460, 580], [600, 780]];
const GROSS_SPREAD = [[1.0, 1.125], [1.0, 1.25], [1.125, 1.375], [1.25, 1.5], [1.5, 1.75], [1.625, 2.0], [2.0, 2.5]];
const UST = { 5: 3.95, 7: 4.08, 8: 4.14, 10: 4.26 };
const CALL = { 5: 2, 7: 3, 8: 3, 10: 5 };

const BANKS = ['Northbridge Capital Markets', 'Harlow & Pierce', 'Meridian Securities', 'Oakmont Bank', 'Crestline Partners',
  'Bayview Securities', 'Granite Point Capital', 'Lakeshore Securities', 'Ashford Brothers'];
const SPONSORS = ['Ridgeline Capital Partners', 'Blue Harbor Equity', 'Kestrel Partners', 'Ironwood Capital', 'Summitview Partners', 'Larkspur Equity'];
const INVESTOR_TYPES = ['High yield funds', 'Asset managers', 'Insurance companies', 'Hedge funds', 'Pension funds', 'CLOs', 'Private banks & retail'];
const INVESTOR_WEIGHTS = [34, 22, 12, 12, 6, 8, 6];
const REGIONS = ['United States', 'Europe', 'Asia & other'];

// dummy issuers: name, industry, sector, city, state
const ISSUERS = [
  ['Harbor Packaging', 'Materials', 'Containers & Packaging', 'Toledo', 'OH'],
  ['Vale Container Co', 'Materials', 'Containers & Packaging', 'Atlanta', 'GA'],
  ['Summit Fibre Pack', 'Materials', 'Paper Packaging', 'Green Bay', 'WI'],
  ['Ridgeway Foods', 'Consumer', 'Packaged Foods', 'Minneapolis', 'MN'],
  ['Copperline Communications', 'Telecom', 'Cable & Fiber', 'Denver', 'CO'],
  ['Bluewater Midstream', 'Energy', 'Oil & Gas Midstream', 'Houston', 'TX'],
  ['Ironclad Building Products', 'Industrials', 'Building Products', 'Charlotte', 'NC'],
  ['Pinecrest Healthcare Services', 'Healthcare', 'Health Care Services', 'Nashville', 'TN'],
  ['Northstar Leisure Group', 'Hospitality', 'Leisure Facilities', 'Orlando', 'FL'],
  ['Atlas Rail Services', 'Transportation', 'Rail Services', 'Chicago', 'IL'],
  ['Keystone Chemicals', 'Materials', 'Specialty Chemicals', 'Pittsburgh', 'PA'],
  ['Redfield Auto Parts', 'Automotive', 'Auto Parts & Equipment', 'Detroit', 'MI'],
  ['Silverpeak Software', 'Technology', 'Application Software', 'Austin', 'TX'],
  ['Granite Bay Hospitality', 'Hospitality', 'Hotels & Resorts', 'Las Vegas', 'NV'],
  ['Lakemont Waste Solutions', 'Industrials', 'Environmental Services', 'Cleveland', 'OH'],
  ['Brightwell Media', 'Media', 'Broadcasting', 'New York', 'NY'],
  ['Crestmoor Energy', 'Energy', 'Exploration & Production', 'Oklahoma City', 'OK'],
  ['Evergreen Senior Living', 'Healthcare', 'Senior Living', 'Portland', 'OR'],
  ['Falconer Aerospace', 'Aerospace & Defense', 'Aerospace Components', 'Wichita', 'KS'],
  ['Highland Metals', 'Mining', 'Steel & Metals', 'Birmingham', 'AL'],
  ['Oakridge Specialty Pharma', 'Healthcare', 'Specialty Pharmaceuticals', 'Princeton', 'NJ'],
  ['Prairie Wind Power', 'Utilities', 'Renewable Power', 'Des Moines', 'IA'],
  ['Quarry Hill Materials', 'Materials', 'Construction Materials', 'Richmond', 'VA'],
  ['Riverbend Logistics', 'Transportation', 'Trucking & Logistics', 'Memphis', 'TN'],
  ['Stonegate Home Care', 'Healthcare', 'Home Health Care', 'Columbus', 'OH'],
  ['Tidemark Marine Services', 'Energy', 'Offshore Services', 'New Orleans', 'LA'],
  ['Westbrook Retail Group', 'Consumer', 'Specialty Retail', 'Boston', 'MA'],
  ['Cobalt Data Centers', 'Technology', 'Data Centers', 'Phoenix', 'AZ'],
  ['Meadowlark Pet Brands', 'Consumer', 'Pet Products', 'Kansas City', 'MO'],
  ['Juniper Fitness Clubs', 'Consumer', 'Leisure & Fitness', 'San Diego', 'CA'],
];
const DESCRIBE = (name, sector, city) => `${name} is a ${sector.toLowerCase()} company headquartered in ${city}.`;
const PURPOSE = [
  ['Refinancing', 'Refinance existing senior notes due within two years', 4],
  ['Refinancing', 'Repay outstanding term loan borrowings', 3],
  ['LBO', 'Fund the sponsor buyout of the company, with a new term loan and equity contribution', 2],
  ['Acquisition', 'Finance the acquisition of a competitor', 2],
  ['Dividend Recap', 'Fund a dividend to shareholders', 1],
  ['General Corporate Purposes', 'General corporate purposes, including capital expenditures', 1],
];

// ------------------------------------------------------------------ build
const docs = Object.fromEntries(COLLECTIONS.map((c) => [c, []]));

// issuers: the dummy companies plus the bank's high-yield-rated clients
const clients = bank.clients.find({ externalRating: { $in: ['BB+', 'BB'] } }).sort({ clientId: 1 }).toArray();
const issuerSpecs = [
  ...ISSUERS.map(([name, industry, sector, city, st]) => ({ name, industry, sector, city, region: st, country: 'USA' })),
  ...clients.map((c) => ({ name: c.name, industry: c.industry, sector: c.industry, city: c.city, country: c.country, client: c })),
];
const issuers = issuerSpecs.map((s, i) => {
  const ratingIx = s.client ? SP.indexOf(s.client.externalRating) : weighted([[0, 1], [1, 2], [2, 3], [3, 3], [4, 3], [5, 2], [6, 1]]);
  const ownership = s.client ? 'Public' : weighted([['Public', 4], ['Sponsor-backed', 5], ['Private', 1]]);
  const revenue = s.client ? s.client.annualRevenueUsd : roundTo(between(400, 6000) * M, M);
  const ebitda = roundTo(revenue * between(0.1, 0.24), 0.1 * M);
  const doc = {
    _id: `HYI-${pad(i + 1, 3)}`, issuerId: `HYI-${pad(i + 1, 3)}`, name: s.name,
    industry: s.industry, sector: s.sector, description: DESCRIBE(s.name, s.sector, s.city),
    hqCity: s.city, hqCountry: s.country, ownership,
    foundedYear: int(1948, 2012), employees: roundTo(revenue / between(180e3, 420e3), 10),
    ltmRevenueUsd: revenue, ltmEbitdaUsd: ebitda, ebitdaMarginPct: r1((ebitda / revenue) * 100),
    corporateRatingSp: SP[ratingIx], corporateRatingMoodys: MOODYS[ratingIx],
    ratingOutlook: weighted([['Stable', 6], ['Positive', 2], ['Negative', 2]]),
    website: `www.${s.name.toLowerCase().replace(/[^a-z]/g, '')}.example`,
  };
  if (s.region) doc.hqState = s.region;
  if (s.client) doc.clientId = s.client.clientId;
  if (ownership === 'Public') doc.ticker = s.name.toUpperCase().replace(/[^A-Z]/g, '').slice(0, 4);
  if (ownership === 'Sponsor-backed') doc.sponsor = pick(SPONSORS);
  docs.hy_issuers.push(doc);
  return { doc, ratingIx };
});

// deals: one priced deal per issuer in the last year (spread evenly), a second (earlier) deal for some, and six in market now
const plans = [];
const slot = 357 / issuers.length;
shuffle(issuers).forEach((iss, k) => {
  plans.push({ iss, daysAgo: int(Math.ceil(3 + k * slot), Math.floor(3 + (k + 1) * slot)) });
  if (chance(0.3)) plans.push({ iss, daysAgo: int(200, 365) });
});
shuffle(issuers).slice(0, 6).forEach((iss) => plans.push({ iss, inMarket: true }));
// in market first, then newest priced first
const sortKey = (p) => (p.inMarket ? -1 : p.daysAgo);
plans.sort((a, b) => sortKey(a) - sortKey(b) || (a.iss.doc.issuerId < b.iss.doc.issuerId ? -1 : 1));

const fmtCoupon = (c) => (Math.round(c * 1000) % 10 ? c.toFixed(3) : c.toFixed(2));

plans.forEach((p, n) => {
  const { iss } = p;
  const I = iss.doc;
  const ix = iss.ratingIx;
  const dealId = `HYD-${pad(n + 1, 4)}`;
  const priced = p.inMarket ? null : daysFromAsOf(-p.daysAgo);
  const launch = p.inMarket ? daysFromAsOf(-int(0, 4)) : new Date(priced.getTime() - int(1, 6) * DAY);
  const isClient = !!I.clientId;
  const ourRole = isClient ? weighted([['Lead left', 3], ['Joint book', 2]])
    : weighted([['n/a', 9], ['Co-manager', 6], ['Joint book', 4], ['Lead left', 1]]);
  const [dealType, useOfProceeds] = weighted(PURPOSE.map(([t, u, w]) => [[t, u], w]));

  // leverage and the pro forma debt stack around the new notes
  const leverage = r1(between(...LEVERAGE[ix]));
  const totalDebt = roundTo(leverage * I.ltmEbitdaUsd, 5 * M);
  const cash = roundTo(I.ltmEbitdaUsd * between(0.15, 0.6), M);
  const dealSize = Math.min(2000 * M, Math.max(250 * M, roundTo(totalDebt * between(0.35, 0.7), 25 * M)));

  // tranches
  const nT = weighted([[1, 6], [2, 3.5], [3, 0.5]]);
  const secured = ix >= 2 || dealType === 'LBO';
  const kinds = nT === 1 ? [chance(0.5) && secured ? 'Senior Secured Notes' : 'Senior Notes']
    : nT === 2 ? ['Senior Secured Notes', 'Senior Notes'] : ['Senior Secured Notes', 'Senior Secured Notes', 'Senior Notes'];
  const tenors = nT === 1 ? [pick([5, 7, 8])] : nT === 2 ? [pick([5, 7]), 8] : [5, 7, 8];
  let left = dealSize;
  const tranches = kinds.map((kind, t) => {
    const size = t === nT - 1 ? left : roundTo(dealSize * (nT === 2 ? between(0.5, 0.65) : between(0.3, 0.4)), 25 * M);
    left -= size;
    const tenor = tenors[t];
    const isSecured = kind === 'Senior Secured Notes';
    const spreadBps = int(...(isSecured ? SPREAD_SECURED : SPREAD_UNSECURED)[ix]);
    // at par the coupon is the yield rounded to an eighth; with an original issue discount it sits an eighth or more below
    const oid = chance(0.3);
    const marketYield = r2(UST[tenor] + spreadBps / 100);
    const couponPct = oid ? Math.floor(marketYield * 8 - 1) / 8 : Math.round(marketYield * 8) / 8;
    const yieldPct = oid ? marketYield : couponPct;
    const issuePrice = oid ? r3(100 - (yieldPct - couponPct) * tenor * 0.78) : 100;
    const settle = priced ? new Date(priced.getTime() + 7 * DAY) : daysFromAsOf(int(8, 14));
    const maturity = addYears(settle, tenor);
    const nc = CALL[tenor];
    const trRating = notch(ix, isSecured ? -1 : (ix >= 3 ? 1 : 0));
    const trancheId = `${dealId}-T${t + 1}`;
    const doc = {
      _id: trancheId, trancheId, dealId, issuerId: I.issuerId,
      // the coupon is only part of the name once the deal has priced
      trancheName: priced ? `${fmtCoupon(couponPct)}% due ${maturity.getUTCFullYear()}` : `${isSecured ? 'Secured' : 'Unsecured'} notes due ${maturity.getUTCFullYear()}`,
      securityDescription: `${priced ? fmtCoupon(couponPct) + '% ' : ''}${kind} due ${maturity.getUTCFullYear()}`,
      seniority: isSecured ? 'Senior Secured' : 'Senior Unsecured', ranking: isSecured ? 'First lien' : 'Unsecured',
      sizeUsd: size, tenorYears: tenor, maturityDate: maturity,
      callProtection: `NC${nc}`, firstCallDate: addYears(settle, nc),
      callSchedule: [
        { callDate: addYears(settle, nc), callPrice: r3(100 + couponPct / 2) },
        { callDate: addYears(settle, nc + 1), callPrice: r3(100 + couponPct / 4) },
        { callDate: addYears(settle, nc + 2), callPrice: 100 },
      ],
      equityClawbackPct: 40, equityClawbackPrice: r3(100 + couponPct), changeOfControlPrice: 101,
      ratingSp: SP[trRating], ratingMoodys: MOODYS[trRating],
      benchmark: `UST ${tenor}y`, format: weighted([['144A / Reg S', 8], ['144A for life', 2]]),
      minDenominationUsd: 2000, cusip: `${pad(int(10000, 99999), 5)}${String.fromCharCode(65 + int(0, 25))}${String.fromCharCode(65 + int(0, 25))}${int(1, 9)}`,
    };
    if (priced) {
      Object.assign(doc, { couponPct, issuePrice, yieldPct, spreadBps: Math.round((yieldPct - UST[tenor]) * 100), orderbookUsd: roundTo(size * between(1.6, 5.2), 5 * M) });
    } else {
      const talk = roundTo(yieldPct + between(0, 0.25), 0.125);
      Object.assign(doc, { priceTalkLowPct: talk, priceTalkHighPct: talk + 0.25 });
    }
    docs.hy_tranches.push(doc);

    // allocations (priced only): by investor type and by region, percent of the tranche
    if (priced) {
      const split = (labels, weights, dim) => {
        const raw = weights.map((w) => w * between(0.6, 1.4));
        const total = raw.reduce((s, v) => s + v, 0);
        const pcts = raw.map((v) => r1((v / total) * 100));
        pcts[0] = r1(100 - pcts.slice(1).reduce((s, v) => s + v, 0));
        let amtLeft = size;
        labels.forEach((label, k) => {
          const amt = k === labels.length - 1 ? amtLeft : roundTo(size * pcts[k] / 100, 0.1 * M);
          amtLeft -= amt;
          docs.hy_allocations.push({
            _id: `${trancheId}-AL-${dim === 'Investor type' ? 'I' : 'R'}${k + 1}`, dealId, trancheId, issuerId: I.issuerId,
            dimension: dim, category: label, allocationPct: pcts[k], allocationUsd: amt,
          });
        });
      };
      split(INVESTOR_TYPES, INVESTOR_WEIGHTS, 'Investor type');
      split(REGIONS, [82, 13, 5], 'Region');
    }
    return doc;
  });
  const lead = tranches.reduce((a, b) => (b.sizeUsd > a.sizeUsd ? b : a));

  // syndicate: the left lead, joint bookrunners and co-managers; Citizens' seat follows ourRole
  const others = shuffle(BANKS);
  const syndicate = [];
  const books = int(2, 4);
  const cms = int(1, 3);
  if (ourRole === 'Lead left') syndicate.push({ bank: 'Citizens', role: 'Lead left' }); else syndicate.push({ bank: others.pop(), role: 'Lead left' });
  for (let k = 1; k < books; k++) syndicate.push({ bank: k === 1 && ourRole === 'Joint book' ? 'Citizens' : others.pop(), role: 'Joint book' });
  for (let k = 0; k < cms; k++) syndicate.push({ bank: k === 0 && ourRole === 'Co-manager' ? 'Citizens' : others.pop(), role: 'Co-manager' });

  const deal = {
    _id: dealId, dealId, issuerId: I.issuerId, issuerName: I.name, market: 'High Yield',
    status: priced ? 'Priced' : 'In Market', ourRole, dealType, useOfProceeds,
    launchDate: launch, dealSizeUsd: dealSize, trancheCount: nT,
    leadTrancheId: lead.trancheId, callProtection: lead.callProtection,
    syndicate, ratingSp: I.corporateRatingSp, ratingMoodys: I.corporateRatingMoodys,
  };
  if (I.clientId) deal.clientId = I.clientId;
  if (priced) {
    Object.assign(deal, {
      pricedDate: priced, settlementDate: new Date(priced.getTime() + 7 * DAY),
      couponPct: lead.couponPct, yieldPct: lead.yieldPct,
      orderbookUsd: tranches.reduce((s, t) => s + t.orderbookUsd, 0),
    });
    deal.oversubscriptionX = r1(deal.orderbookUsd / dealSize);
  } else {
    Object.assign(deal, { expectedPricingDate: daysFromAsOf(int(1, 7)), priceTalkLowPct: lead.priceTalkLowPct, priceTalkHighPct: lead.priceTalkHighPct });
  }
  docs.hy_deals.push(deal);

  // capital structure, pro forma for the deal: revolver, term loan, the new notes, other debt; cumulative leverage
  const stack = [];
  const rcfCommit = roundTo(I.ltmEbitdaUsd * between(0.5, 1.0), 5 * M);
  const rcfDrawn = roundTo(rcfCommit * between(0, 0.3), 5 * M);
  const rest = totalDebt - dealSize - rcfDrawn;
  const tl = secured && rest > 50 * M ? roundTo(rest * between(0.55, 0.85), 5 * M) : 0;
  const settleYear = (priced || daysFromAsOf(10)).getUTCFullYear();
  stack.push({ instrument: 'Revolving credit facility', seniority: 'Senior Secured', amountUsd: rcfDrawn, committedUsd: rcfCommit, maturityYear: settleYear + 5, pricing: `SOFR + ${int(175, 325)}` });
  if (tl > 0) stack.push({ instrument: 'Term Loan B', seniority: 'Senior Secured', amountUsd: tl, maturityYear: settleYear + 7, pricing: `SOFR + ${int(250, 425)}` });
  tranches.forEach((t) => stack.push({
    instrument: t.securityDescription, seniority: t.seniority, amountUsd: t.sizeUsd, maturityYear: t.maturityDate.getUTCFullYear(),
    pricing: t.couponPct !== undefined ? `${fmtCoupon(t.couponPct)}%` : `${t.priceTalkLowPct.toFixed(3)}%-${t.priceTalkHighPct.toFixed(3)}% talk`, trancheId: t.trancheId,
  }));
  const other = totalDebt - stack.reduce((s, r) => s + r.amountUsd, 0);
  if (other > 0) stack.push({ instrument: 'Existing senior unsecured notes', seniority: 'Senior Unsecured', amountUsd: other, maturityYear: settleYear + int(2, 6), pricing: `${fmtCoupon(int(40, 72) / 8)}%` });
  const order = { 'Senior Secured': 0, 'Senior Unsecured': 1 };
  stack.sort((a, b) => order[a.seniority] - order[b.seniority]);
  // the deal's leverage is read off the pro forma stack, so it always agrees with the capital structure
  const debt = stack.reduce((s, r) => s + r.amountUsd, 0);
  const securedDebt = stack.filter((r) => r.seniority === 'Senior Secured').reduce((s, r) => s + r.amountUsd, 0);
  Object.assign(deal, {
    totalLeverageX: r1(debt / I.ltmEbitdaUsd), securedLeverageX: r1(securedDebt / I.ltmEbitdaUsd), netLeverageX: r1((debt - cash) / I.ltmEbitdaUsd),
  });
  let cum = 0;
  stack.forEach((r, k) => {
    cum += r.amountUsd;
    const row = {
      _id: `${dealId}-CS-${pad(k + 1, 2)}`, dealId, issuerId: I.issuerId, rank: k + 1, ...r,
      isNewIssue: !!r.trancheId, cumulativeDebtUsd: cum, cumulativeLeverageX: r2(cum / I.ltmEbitdaUsd),
    };
    if (!r.trancheId) delete row.trancheId;
    docs.hy_capital_structure.push(row);
  });
  docs.hy_capital_structure.push({
    _id: `${dealId}-CS-${pad(stack.length + 1, 2)}`, dealId, issuerId: I.issuerId, rank: stack.length + 1,
    instrument: 'Less: cash and equivalents', seniority: 'Cash', amountUsd: -cash, isNewIssue: false,
    cumulativeDebtUsd: debt - cash, cumulativeLeverageX: r2((debt - cash) / I.ltmEbitdaUsd),
  });

  // covenants: the high-yield incurrence package on the notes
  const ebitdaM = I.ltmEbitdaUsd / M;
  const basket = (pct) => roundTo(I.ltmEbitdaUsd * pct, 5 * M);
  const igFallaway = ix <= 1;
  const covs = [
    ['Debt', 'Limitation on indebtedness', `Fixed charge coverage ratio of at least 2.0x to incur ratio debt; general basket the greater of $${Math.round(basket(0.5) / M)}m and 50% of EBITDA`, { ratioThresholdX: 2.0, basketUsd: basket(0.5), basketPctEbitda: 50 }],
    ['Liens', 'Limitation on liens', `Secured debt permitted up to ${r1(Math.max(2.5, deal.securedLeverageX + 0.5))}x secured leverage`, { ratioThresholdX: r1(Math.max(2.5, deal.securedLeverageX + 0.5)) }],
    ['Restricted payments', 'Limitation on restricted payments', `Builder basket of 50% of consolidated net income; general basket of $${Math.round(basket(0.25) / M)}m`, { basketUsd: basket(0.25), basketPctEbitda: 25 }],
    ['Asset sales', 'Limitation on asset sales', `Proceeds reinvested within 365 days; excess proceeds over $${Math.max(25, Math.round(ebitdaM * 0.1))}m offered to noteholders at par`, { basketUsd: Math.max(25, Math.round(ebitdaM * 0.1)) * M }],
    ['Change of control', 'Change of control', 'Offer to repurchase at 101% of principal plus accrued interest', {}],
    ['Affiliates', 'Transactions with affiliates', `Arm's-length terms; board approval above $${Math.max(10, Math.round(ebitdaM * 0.05))}m`, { basketUsd: Math.max(10, Math.round(ebitdaM * 0.05)) * M }],
    ['Mergers', 'Merger, consolidation and sale of assets', 'Successor must assume the notes; no default after giving effect', {}],
    ['Reporting', 'Reports', 'Quarterly and annual financial statements; quarterly investor calls', {}],
  ];
  covs.forEach(([category, name, test, extra], k) => docs.hy_covenants.push({
    _id: `${dealId}-CV-${pad(k + 1, 2)}`, dealId, issuerId: I.issuerId, category, name, test, ...extra,
    suspendsOnInvestmentGrade: igFallaway && !['Change of control', 'Mergers', 'Reporting'].includes(category),
  }));

  // fee economics: gross spread split into management fee, underwriting fee and selling concession, then by bank
  const grossSpreadPct = roundTo(between(...GROSS_SPREAD[ix]), 0.125);
  const grossFee = Math.round(dealSize * grossSpreadPct / 100);
  const share = syndicate.map((s) => (s.role === 'Lead left' ? 35 : s.role === 'Joint book' ? 20 : 5));
  const tot = share.reduce((a, b) => a + b, 0);
  const pcts = share.map((v) => r1((v / tot) * 100));
  pcts[0] = r1(100 - pcts.slice(1).reduce((a, b) => a + b, 0));
  let feeLeft = grossFee;
  const economics = syndicate.map((s, k) => {
    const fee = k === syndicate.length - 1 ? feeLeft : Math.round(grossFee * pcts[k] / 100);
    feeLeft -= fee;
    return { bank: s.bank, role: s.role, economicsPct: pcts[k], feeUsd: fee };
  });
  const ours = economics.find((e) => e.bank === 'Citizens');
  docs.hy_fee_economics.push({
    _id: dealId, dealId, issuerId: I.issuerId, feeStatus: priced ? 'Final' : 'Estimated',
    grossSpreadPct, grossFeeUsd: grossFee,
    managementFeePct: 20, underwritingFeePct: 20, sellingConcessionPct: 60,
    managementFeeUsd: Math.round(grossFee * 0.2), underwritingFeeUsd: Math.round(grossFee * 0.2),
    sellingConcessionUsd: grossFee - 2 * Math.round(grossFee * 0.2),
    syndicate: economics, citizensRole: ourRole,
    citizensEconomicsPct: ours ? ours.economicsPct : 0, citizensFeeUsd: ours ? ours.feeUsd : 0,
  });
});

// ------------------------------------------------------------------ write
COLLECTIONS.forEach((c) => bank.getCollection(c).insertMany(docs[c], { ordered: true }));
bank.hy_deals.createIndex({ market: 1, status: 1, pricedDate: -1 });
bank.hy_deals.createIndex({ issuerId: 1 });
bank.hy_deals.createIndex({ clientId: 1 }, { sparse: true });
['hy_tranches', 'hy_capital_structure', 'hy_covenants', 'hy_allocations'].forEach((c) => bank.getCollection(c).createIndex({ dealId: 1 }));
bank.hy_allocations.createIndex({ trancheId: 1, dimension: 1 });

// ------------------------------------------------------------------ self-checks (read back from the database)
const catalog = JSON.parse(fs.readFileSync(path.join(SEED_DIR, 'catalog-hy.json'), 'utf8'));
const failures = [];
const check = (ok, msg) => { if (!ok) failures.push(msg); };
const all = (c) => bank.getCollection(c).find().toArray();
const near = (a, b, tol) => Math.abs(a - b) <= tol;
const issuerIds = new Set(all('hy_issuers').map((d) => d.issuerId));
const clientIds = new Set(bank.clients.find({}, { clientId: 1 }).toArray().map((c) => c.clientId));
const deals = all('hy_deals');
const dealIds = new Set(deals.map((d) => d.dealId));
const tranchesOf = {};
all('hy_tranches').forEach((t) => (tranchesOf[t.dealId] ||= []).push(t));

all('hy_issuers').forEach((i) => check(!i.clientId || clientIds.has(i.clientId), `hy_issuers ${i.issuerId}: unknown clientId`));
COLLECTIONS.filter((c) => c !== 'hy_issuers').forEach((c) => {
  check(all(c).every((d) => issuerIds.has(d.issuerId)), `${c}: unknown issuerId`);
  if (c !== 'hy_deals') check(all(c).every((d) => dealIds.has(d.dealId)), `${c}: unknown dealId`);
});
deals.forEach((d) => {
  const ts = tranchesOf[d.dealId] || [];
  check(ts.length === d.trancheCount, `hy_deals ${d.dealId}: trancheCount != tranches`);
  check(ts.reduce((s, t) => s + t.sizeUsd, 0) === d.dealSizeUsd, `hy_deals ${d.dealId}: tranche sizes do not sum to dealSizeUsd`);
  const lead = ts.find((t) => t.trancheId === d.leadTrancheId);
  check(lead && lead.callProtection === d.callProtection && lead.couponPct === d.couponPct && lead.yieldPct === d.yieldPct, `hy_deals ${d.dealId}: headline does not match the lead tranche`);
  check((d.status === 'Priced') === !!d.pricedDate, `hy_deals ${d.dealId}: status and pricedDate disagree`);
  check(d.syndicate.some((s) => s.bank === 'Citizens') === (d.ourRole !== 'n/a'), `hy_deals ${d.dealId}: ourRole and syndicate disagree`);
  const cs = all('hy_capital_structure').filter((r) => r.dealId === d.dealId && r.seniority !== 'Cash');
  ts.forEach((t) => check(cs.some((r) => r.trancheId === t.trancheId && r.amountUsd === t.sizeUsd), `hy_capital_structure ${d.dealId}: ${t.trancheId} missing`));
  const fee = bank.hy_fee_economics.findOne({ _id: d.dealId });
  check(fee && near(fee.syndicate.reduce((s, e) => s + e.economicsPct, 0), 100, 0.05) && fee.syndicate.reduce((s, e) => s + e.feeUsd, 0) === fee.grossFeeUsd,
    `hy_fee_economics ${d.dealId}: syndicate does not add up to the gross fee`);
  check(fee && (fee.citizensFeeUsd > 0) === (d.ourRole !== 'n/a'), `hy_fee_economics ${d.dealId}: Citizens fee and ourRole disagree`);
});
all('hy_tranches').forEach((t) => {
  const priced = t.couponPct !== undefined;
  const allocs = all('hy_allocations').filter((a) => a.trancheId === t.trancheId);
  check(priced ? allocs.length > 0 : allocs.length === 0, `hy_allocations ${t.trancheId}: allocations only on priced tranches`);
  ['Investor type', 'Region'].forEach((dim) => {
    const rows = allocs.filter((a) => a.dimension === dim);
    if (!rows.length) return;
    check(near(rows.reduce((s, a) => s + a.allocationPct, 0), 100, 0.05), `hy_allocations ${t.trancheId} ${dim}: percents do not sum to 100`);
    check(rows.reduce((s, a) => s + a.allocationUsd, 0) === t.sizeUsd, `hy_allocations ${t.trancheId} ${dim}: amounts do not sum to the tranche size`);
  });
});

// the dictionary must describe exactly the fields that exist
COLLECTIONS.forEach((c) => {
  const e = catalog.find((x) => x._id === c);
  check(!!e, `catalog-hy: no entry for ${c}`);
  if (!e) return;
  const documented = new Set(e.fields.map((f) => f.name));
  const observed = new Set(bank.getCollection(c).aggregate([
    { $project: { kv: { $objectToArray: '$$ROOT' } } }, { $unwind: '$kv' }, { $group: { _id: '$kv.k' } },
  ]).toArray().map((d) => d._id));
  observed.forEach((f) => check(documented.has(f), `catalog-hy: ${c}.${f} exists in the data but is not documented`));
  documented.forEach((f) => check(observed.has(f), `catalog-hy: ${c}.${f} is documented but never appears in the data`));
});

print(`hy generated (v${GENERATOR_VERSION}) as of ${AS_OF.toISOString().slice(0, 10)}: ` + COLLECTIONS.map((c) => `${c}=${bank.getCollection(c).countDocuments()}`).join(', '));
if (failures.length) {
  print(`SELF-CHECK FAILED (${failures.length}):\n  ` + failures.slice(0, 40).join('\n  '));
  quit(1);
}
print('all self-checks passed');
