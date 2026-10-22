// Runs once, when MongoDB initializes an empty data volume (mounted into /docker-entrypoint-initdb.d).
// Least privilege: each part of the system gets an account that can touch only what it needs.
//
//   root          administration only (created by the image from MONGO_INITDB_ROOT_*); nothing in the stack uses it
//   aipb_app      readWrite on `aipb`  - the backend's layouts and demo book
//   bank_writer   readWrite on `bank`  - the data generator only
//   bank_reader   read      on `bank`  - the MongoDB MCP server: widgets at runtime and the assistant exploring data
//
// bank_reader is the lock: whatever a client asks the MCP server for, MongoDB itself refuses anything outside `bank`.
function need(name) {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set - see .env.example`);
  return v;
}

db.getSiblingDB('aipb').createUser({ user: 'aipb_app', pwd: need('AIPB_APP_PASSWORD'), roles: [{ role: 'readWrite', db: 'aipb' }] });
db.getSiblingDB('bank').createUser({ user: 'bank_writer', pwd: need('BANK_WRITER_PASSWORD'), roles: [{ role: 'readWrite', db: 'bank' }] });
db.getSiblingDB('bank').createUser({ user: 'bank_reader', pwd: need('BANK_READER_PASSWORD'), roles: [{ role: 'read', db: 'bank' }] });
print('created users: aipb_app, bank_writer, bank_reader');
