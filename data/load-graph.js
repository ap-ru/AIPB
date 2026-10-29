// Loads bank.json (from build-bank.js) into Neo4j as a property graph, following the data dictionary: one label per
// dictionary entry (catalog*.json), the entry's properties on each node, and its relationships between nodes.
//
//   NEO4J_URI=bolt://localhost:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=... node data/load-graph.js [bank.json]
//   (or SEED_NEO4J_URI, ... - in the seed container, see Dockerfile and seed.sh)
//   BANK_RESET=true ...      reload even if the graph is already loaded
//
// Order: wipe, constraints and indexes, nodes (batched UNWIND), relationships, (:_Meta) and (:_Catalog), then read-back
// checks - node and relationship counts, and the properties present on each label against the dictionary. A failed
// check exits 1. Dates become UTC datetimes; nested records (storage json in the dictionary) become JSON strings, since
// Neo4j properties cannot nest; nulls are left out (Neo4j cannot store them), so an absent property reads as null.

const fs = require('fs');
const path = require('path');
const neo4j = require('neo4j-driver');

const SEED_DIR = process.env.SEED_DIR || __dirname;
const FILE = process.argv[2] || path.join(SEED_DIR, 'bank.json');
const RESET = process.env.BANK_RESET === 'true';
const BATCH = 1000;
const CATALOGS = ['catalog.json', 'catalog-hy.json', 'catalog-precall-plus.json'];

// SEED_NEO4J_* inside the seed container, where any NEO4J_* variable would be read as a Neo4j setting
const env = (name, fallback) => process.env['SEED_' + name] || process.env[name] || fallback;
const driver = neo4j.driver(env('NEO4J_URI', 'bolt://localhost:7687'),
  neo4j.auth.basic(env('NEO4J_USER', 'neo4j'), env('NEO4J_PASSWORD', '')), { disableLosslessIntegers: true });
const run = async (q, params = {}) => (await driver.executeQuery(q, params, { database: env('NEO4J_DATABASE', 'neo4j') })).records;
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;
const ident = (s) => { if (!IDENT.test(s)) throw new Error(`not an identifier: ${s}`); return s; };

async function main() {
  await waitForServer();
  const loaded = await run('MATCH (m:_Meta) RETURN m.asOfDate AS asOf LIMIT 1');
  if (loaded.length && !RESET) {
    console.log(`graph already loaded (asOfDate ${loaded[0].get('asOf').toString().slice(0, 10)}) - skipping. Set BANK_RESET=true to reload.`);
    return 0;
  }
  const bank = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  const catalog = CATALOGS.flatMap((f) => JSON.parse(fs.readFileSync(path.join(SEED_DIR, f), 'utf8')));
  const entries = catalog.filter((e) => e.collection);

  // ---------------------------------------------------------------- wipe
  await run('MATCH (n) CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS');

  // ---------------------------------------------------------------- constraints and indexes
  for (const e of entries) {
    const L = ident(e._id), k = ident(e.key);
    await run(`CREATE CONSTRAINT ${L.toLowerCase()}_${k.replace(/^_/, '')} IF NOT EXISTS FOR (n:${L}) REQUIRE n.${k} IS UNIQUE`);
    const indexed = new Set(['clientId']);
    e.relationships.forEach((r) => indexed.add(r.via));
    for (const p of indexed) if (p !== e.key && e.properties.some((f) => f.name === p))
      await run(`CREATE INDEX ${L.toLowerCase()}_${ident(p)} IF NOT EXISTS FOR (n:${L}) ON (n.${p})`);
  }
  await run('CREATE INDEX revenuemonth_month IF NOT EXISTS FOR (n:RevenueMonth) ON (n.month)');
  await run('CREATE INDEX signal_detectedat IF NOT EXISTS FOR (n:Signal) ON (n.detectedAt)');
  await run('CREATE INDEX client_industry IF NOT EXISTS FOR (n:Client) ON (n.industry)');
  await run('CALL db.awaitIndexes(60)');

  // ---------------------------------------------------------------- nodes
  const counts = {};
  for (const e of entries) {
    const docs = bank[e.collection] || [];
    const json = new Set(e.properties.filter((f) => f.storage === 'json').map((f) => f.name));
    const rows = docs.map((d) => props(d, json));
    for (let i = 0; i < rows.length; i += BATCH)
      await run(`UNWIND $rows AS r CREATE (n:${ident(e._id)}) SET n = r`, { rows: rows.slice(i, i + BATCH) });
    counts[e._id] = rows.length;
  }

  // ---------------------------------------------------------------- relationships
  const relCounts = [];
  for (const e of entries) {
    for (const r of e.relationships) {
      const [L, W, T, via, on] = [e._id, r.with, r.type, r.via, r.on || r.via].map(ident);
      const create = r.direction === 'in' ? `CREATE (b)-[:${T}]->(a)` : `CREATE (a)-[:${T}]->(b)`;
      const got = await run(`MATCH (a:${L}) WHERE a.${via} IS NOT NULL MATCH (b:${W}) WHERE b.${on} = a.${via} ${create} RETURN count(*) AS n`);
      relCounts.push({ e, r, n: got[0].get('n') });
    }
  }

  // ---------------------------------------------------------------- meta and dictionary
  const meta = bank._meta[0];
  await run(`CREATE (m:_Meta) SET m = $m`, { m: {
    _id: 'bank', asOfDate: datetime(meta.asOfDate.$date), generatorVersion: meta.generatorVersion, seed: meta.seed,
    generatedAt: datetime(meta.generatedAt.$date), loadedAt: datetime(new Date().toISOString()), counts: JSON.stringify(counts),
  } });
  await run('CREATE CONSTRAINT catalog_id IF NOT EXISTS FOR (n:_Catalog) REQUIRE n.id IS UNIQUE');
  await run('UNWIND $rows AS r CREATE (c:_Catalog) SET c = r', { rows: catalog.map((c) => ({ id: c._id, doc: JSON.stringify(c) })) });

  // ---------------------------------------------------------------- read-back checks
  const failures = [];
  const check = (ok, msg) => { if (!ok) failures.push(msg); };
  for (const e of entries) {
    const n = (await run(`MATCH (n:${e._id}) RETURN count(n) AS n`))[0].get('n');
    check(n === counts[e._id], `${e._id}: ${n} nodes, expected ${counts[e._id]}`);
    const docs = bank[e.collection] || [];
    const documented = new Set(e.properties.map((f) => f.name));
    const nonNull = new Set();
    docs.forEach((d) => Object.entries(d).forEach(([k, v]) => { if (v !== null && v !== undefined) nonNull.add(k); }));
    const observed = new Set((await run(`MATCH (n:${e._id}) UNWIND keys(n) AS k RETURN DISTINCT k`)).map((r) => r.get('k')));
    observed.forEach((k) => check(documented.has(k), `${e._id}.${k} is in the graph but not in the dictionary`));
    // a property that is null on every source document cannot be stored, so it is documented but absent
    documented.forEach((k) => check(observed.has(k) || !nonNull.has(k), `${e._id}.${k} is documented and in the data but not in the graph`));
  }
  for (const { e, r, n } of relCounts) {
    const docs = bank[e.collection] || [];
    if (r.cardinality === 'many-to-one') {
      const want = docs.filter((d) => d[r.via] !== undefined && d[r.via] !== null).length;
      check(n === want, `${e._id} ${r.pattern}: ${n} relationships, expected ${want} (one per ${e._id} with ${r.via})`);
    } else {
      check(n > 0, `${e._id} ${r.pattern}: no relationships`);
    }
  }
  const metaCheck = await run('MATCH (m:_Meta) RETURN count(m) AS n, toString(m.asOfDate) AS asOf');
  check(metaCheck.length === 1 && metaCheck[0].get('n') === 1, '_Meta: expected one node');
  const total = Object.values(counts).reduce((s, n) => s + n, 0);
  const rels = relCounts.reduce((s, x) => s + x.n, 0);
  console.log(`graph loaded as of ${meta.asOfDate.$date.slice(0, 10)}: ${entries.length} labels, ${total} nodes, ${rels} relationships, `
    + `_Catalog=${catalog.length}; ` + Object.entries(counts).map(([l, n]) => `${l}=${n}`).join(', '));
  if (failures.length) {
    console.log(`SELF-CHECK FAILED (${failures.length}):\n  ` + failures.slice(0, 40).join('\n  '));
    return 1;
  }
  console.log('all read-back checks passed');
  return 0;
}

/** A document as node properties: dates as UTC datetimes, nested records as JSON, nulls left out. */
function props(doc, json) {
  const out = {};
  for (const [k, v] of Object.entries(doc)) {
    if (v === null || v === undefined) continue;
    if (json.has(k)) out[k] = JSON.stringify(plain(v));
    else if (v && typeof v === 'object' && '$date' in v) out[k] = datetime(v.$date);
    else if (Array.isArray(v)) out[k] = v.map((x) => (x && typeof x === 'object' && '$date' in x ? datetime(x.$date) : x));
    else if (typeof v === 'object') throw new Error(`${k}: a nested object not marked storage json in the dictionary`);
    else if (typeof v === 'number' && Number.isInteger(v)) out[k] = neo4j.int(v);
    else out[k] = v;
  }
  return out;
}

/** Nested values as plain JSON: dates as ISO strings, as the readers return them. */
function plain(v) {
  if (v && typeof v === 'object' && '$date' in v) return v.$date;
  if (Array.isArray(v)) return v.map(plain);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, plain(x)]));
  return v;
}

function datetime(iso) {
  const d = new Date(iso);
  return new neo4j.types.DateTime(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes(),
    d.getUTCSeconds(), d.getUTCMilliseconds() * 1e6, 0);
}

async function waitForServer() {
  for (let i = 0; ; i++) {
    try { await driver.verifyConnectivity(); return; } catch (e) {
      if (i >= 60) throw e;
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
}

main().then(async (code) => { await driver.close(); process.exit(code); }, async (e) => {
  console.error(e.message || e);
  await driver.close();
  process.exit(1);
});
