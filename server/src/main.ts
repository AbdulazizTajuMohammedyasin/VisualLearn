import { join } from "node:path";
import { buildApp } from "./app.ts";
import { consoleMailer, googleVerifier } from "./auth.ts";
import { loadConfig } from "./config.ts";
import { openDb } from "./db.ts";

const config = loadConfig();
const db = openDb(join(config.dataDir, "visuallearn.db"));
// Sign-in codes are logged until a mail provider is configured.
let log: (message: string) => void = console.log;
const app = await buildApp(
  { config, db, now: Date.now, mailer: consoleMailer((m) => log(m)), google: googleVerifier(config.googleClientId) },
  { logger: true },
);
log = (m) => app.log.info(m);

await app.listen({ port: config.port, host: config.host });
if (config.devAuth) app.log.warn("Development auth is on: email code 123456 works for any address. Never use this in production.");

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await app.close();
    db.close();
    process.exit(0);
  });
}
