// A minimal in-memory stand-in for the mongosh `db` object, so the generators (written for mongosh) run under plain Node
// and their data can be written to bank.json for the graph. It supports exactly what the generators use: drop,
// insertOne/insertMany, createIndex (a no-op), countDocuments, findOne/find with equality, $in and $nin filters,
// sort, forEach/toArray, and the one aggregation the self-checks run (the distinct field names of a collection).

function matches(doc, filter) {
  return Object.entries(filter || {}).every(([k, cond]) => {
    const v = doc[k];
    if (cond && typeof cond === 'object' && !(cond instanceof Date) && !Array.isArray(cond)) {
      return Object.entries(cond).every(([op, x]) => {
        if (op === '$in') return x.some((y) => same(v, y));
        if (op === '$nin') return !x.some((y) => same(v, y));
        throw new Error(`memdb: unsupported operator ${op}`);
      });
    }
    return same(v, cond);
  });
}

const same = (a, b) => (a instanceof Date && b instanceof Date ? a.getTime() === b.getTime() : a === b);

function cursor(docs) {
  return {
    sort(spec) {
      const keys = Object.entries(spec);
      docs = [...docs].sort((a, b) => {
        for (const [k, dir] of keys) {
          const x = a[k] instanceof Date ? a[k].getTime() : a[k];
          const y = b[k] instanceof Date ? b[k].getTime() : b[k];
          if (x < y) return -dir;
          if (x > y) return dir;
        }
        return 0;
      });
      return this;
    },
    toArray: () => docs,
    forEach: (f) => docs.forEach(f),
  };
}

function collection() {
  let docs = [];
  return {
    drop() { docs = []; },
    insertOne(d) { docs.push(d); },
    insertMany(ds) { docs.push(...ds); },
    createIndex() {},
    countDocuments: (f) => docs.filter((d) => matches(d, f)).length,
    findOne: (f) => docs.find((d) => matches(d, f)) || null,
    find: (f) => cursor(docs.filter((d) => matches(d, f))),
    aggregate(pipeline) {
      // the self-checks' "which fields exist" pipeline: $project $objectToArray $$ROOT, $unwind, $group by key
      if (JSON.stringify(pipeline[0]) !== JSON.stringify({ $project: { kv: { $objectToArray: '$$ROOT' } } }))
        throw new Error('memdb: unsupported pipeline ' + JSON.stringify(pipeline));
      const keys = new Set();
      docs.forEach((d) => Object.keys(d).forEach((k) => keys.add(k)));
      return cursor([...keys].map((k) => ({ _id: k })));
    },
    all: () => docs,
  };
}

function database() {
  const colls = {};
  const get = (name) => (colls[name] ||= collection());
  const target = { getCollection: get, getSiblingDB: () => db, collections: () => colls };
  const db = new Proxy(target, { get: (t, k) => (k in t ? t[k] : typeof k === 'string' ? get(k) : undefined) });
  return db;
}

module.exports = { database };
