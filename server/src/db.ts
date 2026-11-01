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
  // 2: chat, sync deletions, model routing
  `CREATE TABLE conversations (
     id TEXT PRIMARY KEY,
     user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     title TEXT NOT NULL,
     created_at INTEGER NOT NULL,
     updated_at INTEGER NOT NULL
   );
   CREATE INDEX conversations_by_user ON conversations (user_id, updated_at);
   CREATE TABLE messages (
     id TEXT PRIMARY KEY,
     conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
     user_id TEXT NOT NULL,
     role TEXT NOT NULL,
     status TEXT NOT NULL,
     parts_json TEXT NOT NULL,
     error_json TEXT,
     ui_action_json TEXT,
     created_at INTEGER NOT NULL
   );
   CREATE INDEX messages_by_conversation ON messages (conversation_id, created_at);
   CREATE TABLE message_events (
     message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
     seq INTEGER NOT NULL,
     event TEXT NOT NULL,
     data_json TEXT NOT NULL,
     PRIMARY KEY (message_id, seq)
   );
   CREATE TABLE deletions (
     user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     kind TEXT NOT NULL,
     item_id TEXT NOT NULL,
     deleted_at INTEGER NOT NULL
   );
   CREATE INDEX deletions_by_user ON deletions (user_id, deleted_at);
   CREATE TABLE providers (
     id TEXT PRIMARY KEY,
     kind TEXT NOT NULL,
     npm TEXT NOT NULL,
     base_url TEXT,
     api_key TEXT,
     models_json TEXT NOT NULL
   );
   CREATE TABLE routing (
     id INTEGER PRIMARY KEY,
     json TEXT NOT NULL
   );`,
  // 3: figure jobs
  `CREATE TABLE figures (
     id TEXT PRIMARY KEY,
     user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     conversation_id TEXT REFERENCES conversations(id) ON DELETE SET NULL,
     prompt TEXT NOT NULL,
     theme TEXT NOT NULL,
     status TEXT NOT NULL,
     stage TEXT,
     message TEXT,
     progress REAL,
     title TEXT,
     summary TEXT,
     error_json TEXT,
     session_id TEXT,
     dark_sha TEXT,
     dark_bytes INTEGER,
     light_sha TEXT,
     light_bytes INTEGER,
     queued_at INTEGER NOT NULL,
     created_at INTEGER NOT NULL,
     updated_at INTEGER NOT NULL,
     started_at INTEGER,
     finished_at INTEGER
   );
   CREATE INDEX figures_by_user ON figures (user_id, created_at);
   CREATE INDEX figures_by_status ON figures (status, queued_at);`,
  // 4: admin audit log
  `CREATE TABLE audit_log (
     id INTEGER PRIMARY KEY,
     admin_id TEXT NOT NULL,
     method TEXT NOT NULL,
     path TEXT NOT NULL,
     status INTEGER NOT NULL,
     at INTEGER NOT NULL
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
