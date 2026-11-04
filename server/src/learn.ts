import { spawn } from "node:child_process";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { createWriteStream, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import { createServer } from "node:net";
import { join } from "node:path";
import type { FastifyInstance, FastifyRequest } from "fastify";
import type { Deps } from "./app.ts";
import { ApiError, badRequest, forbidden, notFound } from "./errors.ts";
import { MAX_PDF_BYTES } from "./documents.ts";
import type { Figures } from "./figures.ts";
import type { ModelRegistry, Provider } from "./models.ts";
import type { RateLimiter } from "./ratelimit.ts";

/**
 * Lessons run on LearnHarness (harness/), which is built for one person per
 * server process: it binds its data folder, keys and settings when it starts.
 * So each learner gets a private LearnHarness process, started on demand and
 * stopped when idle. This server signs learners in, picks the models, and
 * forwards only the API routes a learner should reach.
 */

/** A running LearnHarness process for one learner, reachable on 127.0.0.1:[port]. */
export interface LearnInstance {
  port: number;
  /** LearnHarness's per-launch capability: every request must carry it. */
  secret: string;
  stop(): Promise<void>;
  /** Settles when the process ends for any reason. */
  exited: Promise<void>;
}

export interface LearnDirs {
  root: string;
  /** LH_DATA_DIR: lessons, plan, knowledge graph, settings. */
  data: string;
  /** LH_AUTH_FILE: stays empty; keys come in the environment. */
  auth: string;
  /** LH_FILES_ROOT: the only folder its file tools may read. */
  files: string;
  /** HOME for the process, so nothing writes into the server's own home. */
  home: string;
  /** What the process printed since it last started: background failures, like a knowledge graph update, show up only here. */
  log: string;
}

export interface LearnLaunch {
  userId: string;
  dirs: LearnDirs;
  /** Provider keys and paths for this learner's process. */
  env: Record<string, string>;
}

export type LearnLauncher = (launch: LearnLaunch) => Promise<LearnInstance>;

/** How LearnHarness names a provider and the variable its key rides in, per provider package. */
export const LEARN_PROVIDERS: Record<string, { pi: string; envVar: string }> = {
  "@ai-sdk/google": { pi: "google", envVar: "GEMINI_API_KEY" },
  "@ai-sdk/anthropic": { pi: "anthropic", envVar: "ANTHROPIC_API_KEY" },
  "@ai-sdk/openai": { pi: "openai", envVar: "OPENAI_API_KEY" },
};

export const LEARN_SLOTS = ["strong", "cheap", "vision"] as const;
export type LearnSlot = (typeof LEARN_SLOTS)[number];

/** An OpenAI-compatible endpoint (vLLM, SGLang, Ollama…): LearnHarness's one "custom" provider. */
export const CUSTOM_NPM = "@ai-sdk/openai-compatible";

/** What a learner's process is configured with: its settings sections, the auth record, and keys. */
export interface LearnConfig {
  models: Record<LearnSlot, { model: { provider: string; id: string } }> | null;
  /** LearnHarness's `customProvider` settings section, when a slot is on a local endpoint. */
  customProvider: Record<string, unknown> | null;
  /** auth.json: the custom endpoint's authorization record, or empty. */
  auth: Record<string, string>;
  env: Record<string, string>;
}

/** The admin's lesson models in LearnHarness's terms. */
export function learnConfig(models: ModelRegistry): LearnConfig {
  const config: LearnConfig = { models: null, customProvider: null, auth: {}, env: {} };
  const slots = models.routing().learn;
  if (!slots) return config;
  config.models = {} as NonNullable<LearnConfig["models"]>;
  for (const slot of LEARN_SLOTS) {
    const ref = slots[slot];
    const slash = ref.indexOf("/");
    const provider = models.provider(ref.slice(0, slash));
    const id = ref.slice(slash + 1);
    if (provider?.npm === CUSTOM_NPM && provider.baseUrl) {
      config.models[slot] = { model: { provider: "custom", id } };
      if (!config.customProvider) Object.assign(config, customEndpoint(provider));
      continue;
    }
    const mapping = provider && LEARN_PROVIDERS[provider.npm];
    if (!provider || !mapping) throw new Error(`Lesson slot ${slot} uses ${ref}, which LearnHarness can't reach`);
    config.models[slot] = { model: { provider: mapping.pi, id } };
    if (provider.apiKey) config.env[mapping.envVar] = provider.apiKey;
  }
  return config;
}

/**
 * LearnHarness only uses a custom endpoint whose settings and auth record carry
 * the same random binding id, URL and mode (it never sends a key to a URL it
 * wasn't bound to). The key itself goes in the environment, never on disk.
 */
function customEndpoint(p: Provider): Pick<LearnConfig, "customProvider" | "auth" | "env"> {
  // The same canonical form as LearnHarness's normalizeBaseUrl, or the binding wouldn't match.
  const baseUrl = new URL(p.baseUrl!.trim()).toString().replace(/\/+$/, "");
  const authMode = p.apiKey ? "apiKey" : "keyless";
  const id = randomBytes(16).toString("hex");
  const info = p.modelInfo ?? {};
  return {
    customProvider: {
      baseUrl,
      authMode,
      credentialBindingId: id,
      models: p.models.map((m) => ({ id: m, ...info[m] })),
    },
    auth: { __learnharness_custom_provider_binding: JSON.stringify({ version: 1, id, baseUrl, authMode }) },
    env: p.apiKey ? { CUSTOM_OPENAI_API_KEY: p.apiKey } : {},
  };
}

interface Entry {
  instance: LearnInstance;
  active: number;
  lastUsed: number;
  /** The admin changed the models: restart once nothing is using it. */
  stale: boolean;
}

export interface LearnPoolOptions {
  launcher: LearnLauncher;
  /** Each learner's folders live under [dir]/<user id>. */
  dir: string;
  maxInstances: number;
  idleMs: number;
}

/** One LearnHarness process per learner who is using lessons right now. */
export class LearnPool {
  private readonly deps: Deps;
  private readonly options: LearnPoolOptions;
  private readonly running = new Map<string, Entry>();
  private readonly starting = new Map<string, Promise<Entry>>();
  private readonly sweep: NodeJS.Timeout;

  constructor(deps: Deps, options: LearnPoolOptions) {
    this.deps = deps;
    this.options = options;
    mkdirSync(options.dir, { recursive: true });
    this.sweep = setInterval(() => void this.stopIdle(), Math.min(60_000, options.idleMs));
    this.sweep.unref();
  }

  /** The learner's process, started if needed; call release() when the request is over. */
  async acquire(userId: string): Promise<{ instance: LearnInstance; release: () => void }> {
    let entry = this.running.get(userId);
    if (!entry) {
      let pending = this.starting.get(userId);
      if (!pending) {
        await this.makeRoom();
        pending = this.start(userId).finally(() => this.starting.delete(userId));
        this.starting.set(userId, pending);
      }
      entry = await pending;
    }
    entry.active++;
    entry.lastUsed = this.deps.now();
    let released = false;
    const held = entry;
    return {
      instance: entry.instance,
      release: () => {
        if (released) return;
        released = true;
        held.active--;
        held.lastUsed = this.deps.now();
        if (held.stale && held.active === 0) void this.stop(userId, held);
      },
    };
  }

  /** The admin changed the lesson models: every process restarts with them, idle ones now. */
  reconfigure() {
    for (const [userId, entry] of this.running) {
      entry.stale = true;
      if (entry.active === 0) void this.stop(userId, entry);
    }
  }

  /** Stops the learner's process and deletes their lessons, graph and files. */
  async forget(userId: string) {
    await this.starting.get(userId)?.catch(() => {});
    const entry = this.running.get(userId);
    if (entry) await this.stop(userId, entry);
    rmSync(this.dirs(userId).root, { recursive: true, force: true });
  }

  /** Stops every process (server shutdown). */
  async close() {
    clearInterval(this.sweep);
    await Promise.all([...this.running].map(([userId, entry]) => this.stop(userId, entry)));
  }

  stats() {
    return {
      running: this.running.size,
      starting: this.starting.size,
      active: [...this.running.values()].reduce((n, e) => n + e.active, 0),
      maxInstances: this.options.maxInstances,
    };
  }

  dirs(userId: string): LearnDirs {
    if (!/^[A-Za-z0-9_-]+$/.test(userId)) throw new Error("Unexpected user id");
    const root = join(this.options.dir, userId);
    return { root, data: join(root, "data"), auth: join(root, "auth.json"), files: join(root, "files"), home: join(root, "home"), log: join(root, "harness.log") };
  }

  private async start(userId: string): Promise<Entry> {
    const dirs = this.dirs(userId);
    for (const d of [dirs.data, dirs.files, dirs.home]) mkdirSync(d, { recursive: true });
    const config = learnConfig(this.deps.models);
    // The auth file holds no keys: only the custom endpoint's binding record, rewritten every start.
    writeFileSync(dirs.auth, JSON.stringify(config.auth) + "\n", { mode: 0o600 });
    writeSettings(join(dirs.data, "settings.json"), config);
    const env = config.env;
    const instance = await this.options.launcher({
      userId,
      dirs,
      env: {
        ...env,
        ...this.figureEnv(userId),
        LH_DATA_DIR: dirs.data,
        LH_AUTH_FILE: dirs.auth,
        LH_FILES_ROOT: dirs.files,
        HOME: dirs.home,
        USERPROFILE: dirs.home,
      },
    });
    const entry: Entry = { instance, active: 0, lastUsed: this.deps.now(), stale: false };
    this.running.set(userId, entry);
    void instance.exited.then(() => {
      if (this.running.get(userId) === entry) this.running.delete(userId);
    });
    return entry;
  }

  /** Lets the learner's lessons ask this server for 3D figures (see internalFigureRoutes). */
  private figureEnv(userId: string): Record<string, string> {
    if (!this.deps.figures) return {};
    return {
      VL_FIGURE_URL: `http://127.0.0.1:${this.deps.config.port}/v1/internal`,
      VL_FIGURE_TOKEN: learnerToken(this.deps.config.signingSecret, userId),
    };
  }

  private async stop(userId: string, entry: Entry) {
    if (this.running.get(userId) === entry) this.running.delete(userId);
    await entry.instance.stop().catch((e) => this.deps.log(`stopping lessons for ${userId}: ${(e as Error).message}`));
  }

  private async stopIdle() {
    const cutoff = this.deps.now() - this.options.idleMs;
    for (const [userId, entry] of this.running) {
      if (entry.active === 0 && entry.lastUsed <= cutoff) await this.stop(userId, entry);
    }
  }

  /** At capacity, the longest-idle process makes way; if all are busy, the learner waits. */
  private async makeRoom() {
    if (this.running.size + this.starting.size < this.options.maxInstances) return;
    const idle = [...this.running].filter(([, e]) => e.active === 0).sort((a, b) => a[1].lastUsed - b[1].lastUsed)[0];
    if (!idle) {
      throw new ApiError(503, "lessons_busy", "Lessons are at capacity right now. Try again in a minute.", { retryable: true, headers: { "Retry-After": "30" } });
    }
    await this.stop(idle[0], idle[1]);
  }
}

/** Keeps the learner's own settings and sets the models and endpoint to the admin's choice. */
function writeSettings(file: string, config: LearnConfig) {
  let current: Record<string, unknown> = {};
  try {
    current = JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>;
  } catch {
    // First start, or unreadable: start fresh.
  }
  for (const [key, value] of [["models", config.models], ["customProvider", config.customProvider]] as const) {
    if (value) current[key] = value;
    else delete current[key];
  }
  writeFileSync(file, JSON.stringify(current, null, 2));
}

// --- launching the real thing ---

/** Variables a Node process needs from the host; nothing else of this server's environment is passed on. */
const HOST_ENV = ["PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "TZ"];

/** The environment a learner's process starts with: host basics, its own paths and keys, and the launch secret. */
export function learnEnv(launch: LearnLaunch, extra: { port: number; secret: string; chromePath: string; dev: boolean }): Record<string, string> {
  const env: Record<string, string> = {};
  for (const name of HOST_ENV) if (process.env[name] !== undefined) env[name] = process.env[name]!;
  return {
    ...env,
    ...launch.env,
    ...(extra.chromePath ? { CHROME_PATH: extra.chromePath } : {}),
    LH_DESKTOP_SERVER: "1",
    LH_DESKTOP_API_SECRET: extra.secret,
    PORT: String(extra.port),
    HOSTNAME: "127.0.0.1",
    NODE_ENV: extra.dev ? "development" : "production",
    NEXT_TELEMETRY_DISABLED: "1",
  };
}

/** A learner's process log stops growing here until the process restarts. */
const MAX_LOG_BYTES = 5 * 1024 * 1024;

/**
 * Starts LearnHarness's standalone build (`next build` + `node scripts/build-server.mjs`
 * in harness/), or `next dev` for a single learner while developing.
 */
export function harnessLauncher(options: { harnessDir: string; dev: boolean; chromePath: string; readyTimeoutMs: number; log: (m: string) => void }): LearnLauncher {
  return async (launch) => {
    const port = await freePort();
    const secret = randomBytes(32).toString("hex");
    const standalone = join(options.harnessDir, ".next", "standalone");
    const [cwd, args] = options.dev
      ? [options.harnessDir, [join(options.harnessDir, "node_modules", "next", "dist", "bin", "next"), "dev", "-p", String(port), "-H", "127.0.0.1"]]
      : [standalone, [join(standalone, "server.js")]];
    if (!options.dev && !existsSync(args[0])) throw new Error(`No LearnHarness build at ${args[0]}; run next build and scripts/build-server.mjs in harness/`);
    const child = spawn(process.execPath, args, {
      cwd,
      env: learnEnv(launch, { port, secret, chromePath: options.chromePath, dev: options.dev }),
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    let tail = "";
    mkdirSync(launch.dirs.root, { recursive: true });
    const log = createWriteStream(launch.dirs.log, { flags: "w" });
    let logged = 0;
    const keep = (chunk: Buffer) => {
      tail = (tail + chunk.toString()).slice(-2000);
      if (logged < MAX_LOG_BYTES) log.write(chunk);
      logged += chunk.length;
    };
    child.once("exit", () => log.end());
    child.stdout.on("data", keep);
    child.stderr.on("data", keep);
    const exited = new Promise<void>((resolve) => child.once("exit", () => resolve()));
    const stop = async () => {
      if (child.exitCode !== null || child.signalCode !== null) return;
      child.kill("SIGTERM");
      const timer = setTimeout(() => child.kill("SIGKILL"), 5000);
      await exited;
      clearTimeout(timer);
    };
    try {
      await waitReady(port, secret, exited, options.readyTimeoutMs);
    } catch (e) {
      await stop();
      options.log(`lessons for ${launch.userId} didn't start: ${tail.trim().slice(-500)}`);
      throw e;
    }
    return { port, secret, stop, exited };
  };
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address() as { port: number };
      server.close(() => resolve(port));
    });
  });
}

/** Polls a cheap read route until the process answers it. */
async function waitReady(port: number, secret: string, exited: Promise<void>, timeoutMs: number) {
  let gone = false;
  void exited.then(() => (gone = true));
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (gone) throw new Error("LearnHarness exited while starting");
    const ok = await new Promise<boolean>((resolve) => {
      const req = http.get({ host: "127.0.0.1", port, path: "/api/chats", headers: { "x-learnharness-desktop": secret }, timeout: 5000 }, (res) => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.on("error", () => resolve(false));
      req.on("timeout", () => req.destroy());
    });
    if (ok) return;
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error("LearnHarness didn't start in time");
}

// --- the gateway ---

/**
 * The LearnHarness routes a learner may use: lessons, practice, the knowledge
 * graph and their background work. Keys, providers, subscriptions, Claude Code,
 * prompts and settings changes are the administrator's, so they are not forwarded.
 */
const ALLOWED: [RegExp, RegExp][] = [
  [/^(GET|POST)$/, /^\/api\/chats$/],
  [/^(GET|PATCH|DELETE)$/, /^\/api\/chats\/[\w-]+$/],
  [/^GET$/, /^\/api\/chats\/[\w-]+\/(stream|markdown|artifact-ui|settings)$/],
  [/^POST$/, /^\/api\/chats\/[\w-]+\/(dismiss|steer)$/],
  [/^POST$/, /^\/api\/chat$/],
  [/^GET$/, /^\/api\/(events|jobs|activity|search|models|settings)$/],
  [/^POST$/, /^\/api\/activity\/abort$/],
  [/^GET$/, /^\/api\/graph(\/(costs|status|sources|render-settings))?$/],
  [/^(GET|DELETE)$/, /^\/api\/graph\/nodes\/[\w-]+$/],
  [/^(PUT|POST)$/, /^\/api\/graph\/positions$/],
  [/^POST$/, /^\/api\/graph\/(import|coverage\/extract)$/],
  [/^(DELETE|PATCH|POST)$/, /^\/api\/graph\/sources$/],
];

export function learnAllowed(method: string, path: string): boolean {
  return ALLOWED.some(([m, p]) => m.test(method) && p.test(path));
}

/** New lessons, answers and imports use models: they count against the work limit. */
const WORK = [/^\/api\/chat$/, /^\/api\/graph\/(import|coverage\/extract)$/];

const HOP_BY_HOP = new Set(["connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailer", "proxy-authorization", "proxy-authenticate", "host", "authorization", "cookie", "content-length"]);

/** `/v1/learn/api/...` → the learner's own LearnHarness `/api/...`. */
export function learnRoutes(app: FastifyInstance, deps: Deps, pool: LearnPool, work: RateLimiter) {
  app.register(async (scope) => {
    // Bodies pass through untouched: JSON, multipart note imports, anything.
    scope.removeAllContentTypeParsers();
    scope.addContentTypeParser("*", { parseAs: "buffer", bodyLimit: 27 * 1024 * 1024 }, (_request, body, done) => done(null, body));

    // A PDF from the phone goes into the learner's files folder, the only place
    // LearnHarness's file tools and PDF reader may read; the reply says how to refer to it.
    scope.post("/learn/files", async (request, reply) => {
      const pdf = request.body;
      if (!Buffer.isBuffer(pdf) || pdf.length === 0) throw badRequest("Send the PDF as the request body.");
      if (pdf.length > MAX_PDF_BYTES) throw badRequest("That PDF is too big. The limit is 25 MB.");
      if (pdf.subarray(0, 5).toString("latin1") !== "%PDF-") throw badRequest("That file isn't a PDF.");
      work.hit(request.user.id);
      const name = learnFileName((request.query as { name?: string }).name ?? "");
      const files = pool.dirs(request.user.id).files;
      mkdirSync(files, { recursive: true });
      writeFileSync(join(files, name), pdf);
      return reply.code(201).send({ name, path: `~/${name}`, bytes: pdf.length });
    });

    scope.all("/learn/*", async (request, reply) => {
      const url = new URL(request.url, "http://local");
      const path = url.pathname.replace(/^\/v1\/learn/, "");
      if (!learnAllowed(request.method, path)) throw forbidden();
      if (request.method === "POST" && WORK.some((w) => w.test(path))) work.hit(request.user.id);

      const { instance, release } = await pool.acquire(request.user.id);
      const headers: Record<string, string> = {};
      for (const [name, value] of Object.entries(request.headers)) {
        if (!HOP_BY_HOP.has(name) && typeof value === "string") headers[name] = value;
      }
      headers["x-learnharness-desktop"] = instance.secret;
      const body = Buffer.isBuffer(request.body) ? request.body : null;
      if (body) headers["content-length"] = String(body.length);

      reply.hijack();
      const res = reply.raw;
      const upstream = http.request({ host: "127.0.0.1", port: instance.port, method: request.method, path: path + url.search, headers }, (answer) => {
        const out: Record<string, string | string[]> = {};
        for (const [name, value] of Object.entries(answer.headers)) {
          if (value !== undefined && !HOP_BY_HOP.has(name)) out[name] = value;
        }
        // Event streams must reach the phone as they happen.
        if (String(answer.headers["content-type"] ?? "").startsWith("text/event-stream")) out["x-accel-buffering"] = "no";
        res.writeHead(answer.statusCode ?? 502, out);
        answer.pipe(res);
        answer.on("end", release);
        answer.on("error", release);
      });
      upstream.on("error", (e) => {
        release();
        deps.log(`lesson request for ${request.user.id} failed: ${e.message}`);
        if (!res.headersSent) {
          res.writeHead(502, { "content-type": "application/json" });
          res.end(JSON.stringify({ error: { code: "lessons_unavailable", message: "Lessons are restarting. Try again in a moment.", retryable: true } }));
        } else res.destroy();
      });
      // The phone went away: stop the upstream read (the lesson itself keeps running there).
      res.on("close", () => {
        if (!res.writableFinished) upstream.destroy();
        release();
      });
      upstream.end(body ?? undefined);
    });
  });
}

// --- figures for lessons ---

/** What a learner's process presents to ask for figures: their id, signed by this server. */
export function learnerToken(signingSecret: string, userId: string): string {
  return `${userId}.${createHmac("sha256", signingSecret).update(`learner:${userId}`).digest("base64url")}`;
}

function learnerFromToken(signingSecret: string, token: string | undefined): string | null {
  if (!token) return null;
  const userId = token.slice(0, token.indexOf("."));
  if (!userId) return null;
  const expected = Buffer.from(learnerToken(signingSecret, userId));
  const given = Buffer.from(token);
  return expected.length === given.length && timingSafeEqual(expected, given) ? userId : null;
}

/**
 * `/v1/internal/figures`: LearnHarness's `figure` job asks for 3D figures here on
 * its learner's behalf (harness/src/agent/subagents/figure-client.ts). Only from
 * this machine, only with the learner token the process was started with, and
 * only for that learner's own figures. They join the same fair-share queue and
 * appear in the learner's figures like any other.
 */
export function internalFigureRoutes(app: FastifyInstance, deps: Deps, figures: Figures, work: RateLimiter) {
  app.register(async (scope) => {
    scope.addHook("onRequest", async (request) => {
      const local = ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(request.socket.remoteAddress ?? "");
      const userId = learnerFromToken(deps.config.signingSecret, request.headers["x-visuallearn-learner"] as string | undefined);
      if (!local || !userId) throw forbidden();
      (request as unknown as { learnerId: string }).learnerId = userId;
    });
    const learner = (request: FastifyRequest) => (request as unknown as { learnerId: string }).learnerId;
    const own = (request: FastifyRequest) => {
      const f = figures.get((request.params as { id: string }).id);
      if (!f || f.user_id !== learner(request)) throw notFound("This figure");
      return f;
    };

    scope.post("/internal/figures", async (request, reply) => {
      const body = (request.body ?? {}) as { prompt?: unknown };
      if (typeof body.prompt !== "string" || !body.prompt.trim()) throw badRequest("Describe what the figure should show.");
      work.hit(learner(request));
      const id = figures.create(learner(request), body.prompt, null);
      return reply.code(201).send(figures.toJson(figures.get(id)!));
    });
    scope.get("/internal/figures/:id", async (request) => figures.toJson(own(request)));
    scope.post("/internal/figures/:id/cancel", async (request) => {
      const f = own(request);
      if (f.status === "queued" || f.status === "running") figures.cancel(f.id);
      return figures.toJson(own(request));
    });
  });
}

/** A safe file name for the learner's folder: no folders, no hidden files, always `.pdf`. */
export function learnFileName(raw: string): string {
  const base = raw.split(/[\\/]/).pop() ?? "";
  const clean = base
    .replace(/\.pdf$/i, "")
    .replace(/[^\p{L}\p{N} ._()-]+/gu, "-")
    .replace(/^[.\s-]+|[.\s-]+$/g, "")
    .slice(0, 120);
  return `${clean || "document"}.pdf`;
}
