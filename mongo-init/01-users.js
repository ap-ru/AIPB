// Runs once, when MongoDB initializes an empty data volume (mounted into /docker-entrypoint-initdb.d).
// Least privilege: each part of the system gets an account that can touch only what it needs.
//
//   root          administration only (created by the image from MONGO_INITDB_ROOT_*); nothing in the stack uses it
//   aipb_app      readWrite on `aipb`  - the backend's layouts, widget definitions and assistant sessions
//
// The bank's source data is not in MongoDB on this branch: it is a Neo4j graph (see docker-compose.yml).
function need(name) {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set - see .env.example`);
  return v;
}

db.getSiblingDB('aipb').createUser({ user: 'aipb_app', pwd: need('AIPB_APP_PASSWORD'), roles: [{ role: 'readWrite', db: 'aipb' }] });
print('created users: aipb_app');
