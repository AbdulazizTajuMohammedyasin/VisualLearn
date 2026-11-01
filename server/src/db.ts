import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { DatabaseSync } from "node:sqlite";

export type Db = DatabaseSync;

/**
 * Schema changes, applied in order once each. Never edit a shipped entry; append a new one.
 * Timestamps are epoch milliseconds; the API turns them into ISO strings.
 */
const MIGRATIONS: string[] = [
  // 1: accounts
  `CREATE TABLE users (
     id TEXT PRIMARY KEY,
     email TEXT UNIQUE,
     google_sub TEXT UNIQUE,
     display_name TEXT NOT NULL,
     avatar_url TEXT,
     role TEXT NOT NULL DEFAULT 'user',
     created_at INTEGER NOT NULL
   );
   CREATE TABLE sessions (
     refresh_hash TEXT PRIMARY KEY,
     user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     expires_at INTEGER NOT NULL
   );
   CREATE TABLE email_codes (
     email TEXT PRIMARY KEY,
     code_hash TEXT NOT NULL,
     expires_at INTEGER NOT NULL,
     attempts INTEGER NOT NULL DEFAULT 0,
     sent_at INTEGER NOT NULL
   );`,
];

/** Opens (or creates) the database and brings its schema up to date. */
export function openDb(file: string): Db {
  if (file !== ":memory:") mkdirSync(dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
  db.exec("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
  const row = db.prepare("SELECT version FROM schema_version").get() as { version: number } | undefined;
  let version = row?.version ?? 0;
  if (!row) db.prepare("INSERT INTO schema_version (version) VALUES (0)").run();
  while (version < MIGRATIONS.length) {
    transaction(db, () => {
      db.exec(MIGRATIONS[version]);
      db.prepare("UPDATE schema_version SET version = ?").run(version + 1);
    });
    version++;
  }
  return db;
}

export function transaction<T>(db: Db, work: () => T): T {
  db.exec("BEGIN");
  try {
    const result = work();
    db.exec("COMMIT");
    return result;
  } catch (e) {
    db.exec("ROLLBACK");
    throw e;
  }
}
