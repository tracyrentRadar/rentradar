// Runs once, on the first start of the container with an empty data volume.
// The mongo image executes it as the root user created from MONGO_INITDB_ROOT_*.
//
// Creates the application user so the API never connects as the superuser.
// Its roles are scoped to the application database only:
//   readWrite  documents, indexes (including the audit_log TTL index)
//   dbAdmin    collMod, needed to apply the JSON Schema validators at startup
// It holds no cluster-level or cross-database privileges.

const dbName = process.env.MONGO_APP_DATABASE;
const username = process.env.MONGO_APP_USERNAME;
const password = process.env.MONGO_APP_PASSWORD;

if (!dbName || !username || !password) {
  throw new Error(
    "MONGO_APP_DATABASE, MONGO_APP_USERNAME and MONGO_APP_PASSWORD must all be set"
  );
}

db.getSiblingDB(dbName).createUser({
  user: username,
  pwd: password,
  roles: [
    { role: "readWrite", db: dbName },
    { role: "dbAdmin", db: dbName },
  ],
});

print(`Created application user '${username}' on database '${dbName}'`);
