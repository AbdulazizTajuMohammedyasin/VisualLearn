import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { buildApp, type Deps } from "./app.ts";
import { consoleMailer, googleVerifier } from "./auth.ts";
import { loadConfig } from "./config.ts";
import { openDb } from "./db.ts";
import { Figures } from "./figures.ts";
import { ModelRegistry } from "./models.ts";
import { chatSystemPrompt } from "./prompt.ts";
import { DevRunner, OpenCodeRunner, RoutedRunner } from "./runners.ts";

const config = loadConfig();
const db = openDb(join(config.dataDir, "visuallearn.db"));
const models = new ModelRegistry(db);
const openCode = config.anatomyDir
  ? new OpenCodeRunner({ bin: config.openCodeBin, anatomyDir: config.anatomyDir, jobsDir: join(config.dataDir, "jobs"), timeoutMs: config.figureTimeoutMs }, models)
  : null;
const runner = new RoutedRunner(models, new DevRunner(config.devFigureStageMs), openCode);

// The logger exists only once the app does; until then messages go to the console.
let log: (message: string) => void = console.log;
const deps: Deps = {
  config,
  db,
  now: Date.now,
  // Sign-in codes are logged until a mail provider is configured.
  mailer: consoleMailer((m) => log(m)),
  google: googleVerifier(config.googleClientId),
  models,
  figures: null,
  chatSystemPrompt: chatSystemPrompt(existsSync(config.uiPromptFile) ? readFileSync(config.uiPromptFile, "utf8") : null),
  uiLibraryVersion: config.uiLibraryVersion,
  log: (m) => log(m),
};
// Before buildApp: the figure routes are registered only when the service exists.
deps.figures = new Figures(deps, { workers: config.figureWorkers, runner, pagesDir: join(config.dataDir, "pages") });
const app = await buildApp(deps, { logger: true });
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
