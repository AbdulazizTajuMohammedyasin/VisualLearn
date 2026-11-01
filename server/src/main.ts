import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { buildApp } from "./app.ts";
import { consoleMailer, googleVerifier } from "./auth.ts";
import { loadConfig } from "./config.ts";
import { openDb } from "./db.ts";
import { chatSystemPrompt } from "./prompt.ts";
import { ModelRegistry } from "./models.ts";

const config = loadConfig();
const db = openDb(join(config.dataDir, "visuallearn.db"));
// Sign-in codes are logged until a mail provider is configured.
let log: (message: string) => void = console.log;
const app = await buildApp(
  {
    config,
    db,
    now: Date.now,
    mailer: consoleMailer((m) => log(m)),
    google: googleVerifier(config.googleClientId),
    models: new ModelRegistry(db),
    figures: null,
    chatSystemPrompt: chatSystemPrompt(existsSync(config.uiPromptFile) ? readFileSync(config.uiPromptFile, "utf8") : null),
    uiLibraryVersion: config.uiLibraryVersion,
    log: (m) => log(m),
  },
  { logger: true },
);
log = (m) => app.log.info(m);
if (!existsSync(config.uiPromptFile)) app.log.warn(`No OpenUI prompt at ${config.uiPromptFile}; run "npm run prompt" in ui-library. Replies will be text only.`);

await app.listen({ port: config.port, host: config.host });
if (config.devAuth) app.log.warn("Development auth is on: email code 123456 works for any address. Never use this in production.");

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    await app.close();
    db.close();
    process.exit(0);
  });
}
