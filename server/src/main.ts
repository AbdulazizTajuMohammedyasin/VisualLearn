import { join } from "node:path";
import { buildApp } from "./app.ts";
import { loadConfig } from "./config.ts";
import { openDb } from "./db.ts";

const config = loadConfig();
const db = openDb(join(config.dataDir, "visuallearn.db"));
const app = await buildApp({ config, db, now: Date.now }, { logger: true });

await app.listen({ port: config.port, host: config.host });
if (config.devAuth) app.log.warn("Development auth is on: email code 123456 works for any address. Never use this in production.");

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await app.close();
    db.close();
    process.exit(0);
  });
}
