// Runs the three generators (generate.js, generate-hy.js, generate-precall-plus.js) under plain Node against an
// in-memory database (memdb.js), with their self-checks, and writes every collection to one JSON file for the graph
// loader (load-graph.js). The generators stay mongosh scripts, so the MongoDB seed and the graph seed are the same data.
//
//   BANK_AS_OF=2026-10-27 node data/build-bank.js [out.json]        default out: data/bank.json
//
// Dates are written as {"$date": "<ISO instant>"}; everything else is plain JSON.

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { database } = require('./memdb');

const SEED_DIR = process.env.SEED_DIR || __dirname;
const OUT = process.argv[2] || path.join(__dirname, 'bank.json');
const SCRIPTS = ['generate.js', 'generate-hy.js', 'generate-precall-plus.js'];

class Quit extends Error {
  constructor(code) { super(`quit(${code})`); this.code = code; }
}

const db = database();
for (const script of SCRIPTS) {
  const env = { ...process.env, SEED_DIR, BANK_RESET: 'true', HY_RESET: 'true', PRECALL_PLUS_RESET: 'true' };
  const context = vm.createContext({
    db, require, console, Math, Date, JSON, Object, Array, Set, Map, Number, String, Error,
    process: { env }, __dirname: SEED_DIR,
    print: (...a) => console.log(`[${script}]`, ...a),
    quit: (code) => { throw new Quit(code || 0); },
  });
  try {
    vm.runInContext(fs.readFileSync(path.join(SEED_DIR, script), 'utf8'), context, { filename: script });
  } catch (e) {
    if (!(e instanceof Quit)) throw e;
    if (e.code !== 0) { console.error(`${script} failed`); process.exit(e.code); }
  }
}

const out = {};
for (const [name, c] of Object.entries(db.collections()).sort(([a], [b]) => a.localeCompare(b))) {
  if (c.all().length) out[name] = c.all();
}
// Date.prototype.toJSON runs before a replacer sees the value, so tag dates by walking first
const json = JSON.stringify(tagDates(out));
fs.writeFileSync(OUT, json);
console.log(`wrote ${OUT}: ` + Object.entries(out).map(([c, d]) => `${c}=${d.length}`).join(', ') + ` (${(json.length / 1e6).toFixed(1)} MB)`);

function tagDates(v) {
  if (v instanceof Date) return { $date: v.toISOString() };
  if (Array.isArray(v)) return v.map(tagDates);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, tagDates(x)]));
  return v;
}
