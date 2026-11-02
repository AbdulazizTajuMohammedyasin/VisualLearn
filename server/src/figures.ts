import { createHmac, timingSafeEqual } from "node:crypto";
import { EventEmitter } from "node:events";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { FastifyInstance, FastifyRequest } from "fastify";
import type { Deps, FigureService } from "./app.ts";
import { transaction } from "./db.ts";
import { badRequest, conflict, notFound, rateLimited } from "./errors.ts";
import type { Previewer } from "./previews.ts";
import { FigureFailure, STAGES, type FigureRunner, type Stage } from "./runners.ts";
import { iso, newId, sha256 } from "./util.ts";

export interface FigureRow {
  id: string;
  user_id: string;
  conversation_id: string | null;
  prompt: string;
  theme: "dark" | "light";
  status: "queued" | "running" | "succeeded" | "failed" | "cancelled";
  stage: Stage | null;
  message: string | null;
  progress: number | null;
  title: string | null;
  summary: string | null;
  error_json: string | null;
  session_id: string | null;
  dark_sha: string | null;
  dark_bytes: number | null;
  light_sha: string | null;
  light_bytes: number | null;
  queued_at: number;
  created_at: number;
  updated_at: number;
  started_at: number | null;
  finished_at: number | null;
}

const MAX_QUEUED_PER_USER = 10;
const PAGE_URL_TTL_MS = 60 * 60 * 1000;
/** Used for wait estimates until real jobs have finished. */
const DEFAULT_JOB_MS = 40 * 60 * 1000;
const ACTIVE = ["queued", "running"];

export interface FigureOptions {
  workers: number;
  runner: FigureRunner;
  pagesDir: string;
  /** Draws preview images of finished figures; without it previewUrl stays null. */
  previewer?: Previewer;
}

/**
 * Figure jobs: a fair-share queue (section 6) feeding a pool of workers, finished
 * pages kept content-addressed on disk and served through short-lived signed URLs.
 */
export class Figures implements FigureService {
  private readonly deps: Deps;
  private readonly options: FigureOptions;
  private readonly running = new Map<string, AbortController>();
  private readonly updates = new EventEmitter();
  /** Previews are drawn one at a time; each is a browser process. */
  private previewQueue: Promise<void> = Promise.resolve();

  constructor(deps: Deps, options: FigureOptions) {
    this.deps = deps;
    this.options = options;
    this.updates.setMaxListeners(0);
    mkdirSync(options.pagesDir, { recursive: true });
    // Jobs a restart cut off go back to the queue; OpenCode resumes their session.
    deps.db.prepare("UPDATE figures SET status = 'queued', message = 'Waiting for a free worker.' WHERE status = 'running'").run();
    // Figures finished before previews existed, or whose preview failed, get one now.
    if (options.previewer) {
      const shas = deps.db.prepare("SELECT DISTINCT dark_sha FROM figures WHERE status = 'succeeded' AND dark_sha IS NOT NULL").all() as { dark_sha: string }[];
      for (const { dark_sha } of shas) if (!existsSync(this.previewFile(dark_sha))) this.makePreview(dark_sha);
    }
  }

  // --- jobs ---

  create(userId: string, prompt: string, conversationId: string | null, theme: "dark" | "light" = "dark"): string {
    const { db, now } = this.deps;
    const clean = prompt.trim().slice(0, 2000);
    if (!clean) throw badRequest("Describe what the figure should show.");
    const queued = (db.prepare("SELECT COUNT(*) AS n FROM figures WHERE user_id = ? AND status = 'queued'").get(userId) as { n: number }).n;
    if (queued >= MAX_QUEUED_PER_USER) throw rateLimited(60, "You have a lot of figures waiting. Try again when some have started.");
    const id = newId("fig");
    const t = now();
    db.prepare(
      `INSERT INTO figures (id, user_id, conversation_id, prompt, theme, status, message, progress, queued_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 'queued', 'Waiting for a free worker.', 0, ?, ?, ?)`,
    ).run(id, userId, conversationId, clean, theme, t, t, t);
    this.changed();
    return id;
  }

  get(id: string): FigureRow | undefined {
    return this.deps.db.prepare("SELECT * FROM figures WHERE id = ?").get(id) as FigureRow | undefined;
  }

  cancel(id: string) {
    const f = this.get(id);
    if (!f) return;
    if (!ACTIVE.includes(f.status)) throw conflict("This figure has already finished.");
    this.running.get(id)?.abort(new Error("cancelled"));
    this.update(id, { status: "cancelled", message: "Cancelled.", finished_at: this.deps.now() });
    this.changed();
  }

  retry(id: string) {
    const f = this.get(id);
    if (!f) return;
    if (f.status !== "failed" && f.status !== "cancelled") throw conflict("Only stopped figures can be tried again.");
    const t = this.deps.now();
    this.update(id, { status: "queued", stage: null, message: "Waiting for a free worker.", progress: 0, error_json: null, queued_at: t, started_at: null, finished_at: null });
    this.changed();
  }

  /** Deletes a figure, its pages if nothing else uses them, and records it for sync. */
  delete(id: string) {
    const f = this.get(id);
    if (!f) return;
    this.running.get(id)?.abort(new Error("deleted"));
    transaction(this.deps.db, () => {
      this.deps.db.prepare("DELETE FROM figures WHERE id = ?").run(id);
      this.deps.db.prepare("INSERT INTO deletions (user_id, kind, item_id, deleted_at) VALUES (?, 'figure', ?, ?)").run(f.user_id, id, this.deps.now());
    });
    this.dropUnusedPages([f.dark_sha, f.light_sha]);
    this.updates.emit(id, null);
    this.changed();
  }

  /** Before an account is deleted: stop its jobs and remove its pages. */
  deleteForUser(userId: string) {
    const rows = this.deps.db.prepare("SELECT id, dark_sha, light_sha FROM figures WHERE user_id = ?").all(userId) as unknown as FigureRow[];
    for (const f of rows) this.running.get(f.id)?.abort(new Error("deleted"));
    this.deps.db.prepare("DELETE FROM figures WHERE user_id = ?").run(userId);
    this.dropUnusedPages(rows.flatMap((f) => [f.dark_sha, f.light_sha]));
  }

  private dropUnusedPages(shas: (string | null)[]) {
    for (const sha of new Set(shas.filter((s): s is string => !!s))) {
      const used = this.deps.db.prepare("SELECT 1 FROM figures WHERE dark_sha = ? OR light_sha = ? LIMIT 1").get(sha, sha);
      if (!used) {
        rmSync(this.pageFile(sha), { force: true });
        rmSync(this.previewFile(sha), { force: true });
      }
    }
  }

  // --- scheduling ---

  /**
   * The queue in the order jobs will start: round-robin across users, a user with
   * fewer running jobs first, so nobody's tenth job waits ahead of someone's first.
   */
  queueOrder(): FigureRow[] {
    const { db } = this.deps;
    const queued = db.prepare("SELECT * FROM figures WHERE status = 'queued' ORDER BY queued_at, rowid").all() as unknown as FigureRow[];
    const runningByUser = new Map<string, number>();
    for (const r of db.prepare("SELECT user_id, COUNT(*) AS n FROM figures WHERE status = 'running' GROUP BY user_id").all() as { user_id: string; n: number }[]) {
      runningByUser.set(r.user_id, r.n);
    }
    // When each user's last job started: among equally loaded users, the one served longest ago goes first.
    const lastServed = new Map<string, number>();
    for (const r of db.prepare("SELECT user_id, MAX(started_at) AS t FROM figures WHERE started_at IS NOT NULL GROUP BY user_id").all() as { user_id: string; t: number }[]) {
      lastServed.set(r.user_id, r.t);
    }
    const byUser = new Map<string, FigureRow[]>();
    for (const f of queued) byUser.set(f.user_id, [...(byUser.get(f.user_id) ?? []), f]);
    const order: FigureRow[] = [];
    const before = (a: string, b: string) => {
      const load = (runningByUser.get(a) ?? 0) - (runningByUser.get(b) ?? 0);
      if (load !== 0) return load < 0;
      const served = (lastServed.get(a) ?? -Infinity) - (lastServed.get(b) ?? -Infinity);
      if (served !== 0 && !Number.isNaN(served)) return served < 0;
      return byUser.get(a)![0].queued_at < byUser.get(b)![0].queued_at;
    };
    let turn = Number.MAX_SAFE_INTEGER - 1_000_000;
    while (byUser.size) {
      let best: string | null = null;
      for (const user of byUser.keys()) if (best === null || before(user, best)) best = user;
      const jobs = byUser.get(best!)!;
      order.push(jobs.shift()!);
      // Simulate the job starting, so the next pick goes round the other users.
      runningByUser.set(best!, (runningByUser.get(best!) ?? 0) + 1);
      lastServed.set(best!, turn++);
      if (!jobs.length) byUser.delete(best!);
    }
    return order;
  }

  /** Starts queued jobs while workers are free. Called whenever the queue or a worker changes. */
  private schedule() {
    while (this.running.size < this.options.workers) {
      const next = this.queueOrder()[0];
      if (!next) break;
      this.startJob(next);
    }
  }

  private changed() {
    this.schedule();
    // Queue positions moved for everyone waiting.
    for (const f of this.queueOrder()) this.updates.emit(f.id, f.id);
  }

  private startJob(f: FigureRow) {
    const abort = new AbortController();
    this.running.set(f.id, abort);
    this.update(f.id, { status: "running", message: "Starting.", started_at: this.deps.now() });
    const run = async () => {
      try {
        const result = await this.options.runner.run(
          { id: f.id, prompt: f.prompt, theme: f.theme, sessionId: f.session_id },
          {
            signal: abort.signal,
            progress: (stage, message) => {
              if (abort.signal.aborted) return;
              this.update(f.id, { stage, message, progress: (STAGES.indexOf(stage) + 1) / (STAGES.length + 1) });
            },
            session: (id) => this.update(f.id, { session_id: id }, false),
          },
        );
        if (abort.signal.aborted) return;
        const dark = this.storePage(result.dark);
        const light = this.storePage(result.light);
        this.update(f.id, {
          status: "succeeded", stage: "polish", message: "Finished.", progress: 1,
          title: result.title, summary: result.summary, finished_at: this.deps.now(),
          dark_sha: dark.sha, dark_bytes: dark.bytes, light_sha: light.sha, light_bytes: light.bytes,
        });
        this.makePreview(dark.sha);
      } catch (e) {
        if (abort.signal.aborted) return;
        this.deps.log(`figure ${f.id} failed: ${(e as Error)?.message ?? e}`);
        const problem = e instanceof FigureFailure ? e.problem : null;
        this.update(f.id, {
          status: "failed", message: "Stopped.", finished_at: this.deps.now(),
          error_json: JSON.stringify(problem ?? { code: "figure_failed", message: "This figure couldn't be finished. Try again.", retryable: true }),
        });
      } finally {
        this.running.delete(f.id);
        this.changed();
      }
    };
    void run();
  }

  private update(id: string, fields: Partial<FigureRow>, notify = true) {
    const entries = Object.entries({ ...fields, updated_at: this.deps.now() });
    this.deps.db.prepare(`UPDATE figures SET ${entries.map(([k]) => `${k} = ?`).join(", ")} WHERE id = ?`).run(...(entries.map(([, v]) => v ?? null) as (string | number | null)[]), id);
    if (notify) this.updates.emit(id, id);
  }

  // --- pages ---

  private storePage(html: Buffer) {
    const sha = sha256(html);
    const file = this.pageFile(sha);
    if (!existsSync(file)) {
      writeFileSync(`${file}.part`, html);
      renameSync(`${file}.part`, file);
    }
    return { sha, bytes: html.length };
  }

  private pageFile(sha: string) {
    return join(this.options.pagesDir, `${sha}.html`);
  }

  /** The preview of the page with this hash (the dark page's). */
  private previewFile(sha: string) {
    return join(this.options.pagesDir, `${sha}.png`);
  }

  /** Draws the preview in the background, then tells everyone showing a figure with that page. */
  private makePreview(sha: string) {
    const previewer = this.options.previewer;
    if (!previewer) return;
    this.previewQueue = this.previewQueue.then(async () => {
      const out = this.previewFile(sha);
      if (existsSync(out) || !existsSync(this.pageFile(sha))) return;
      try {
        await previewer.render(this.pageFile(sha), `${out}.part.png`);
        renameSync(`${out}.part.png`, out);
      } catch (e) {
        rmSync(`${out}.part.png`, { force: true });
        this.deps.log(`preview of ${sha.slice(0, 12)} failed: ${(e as Error)?.message ?? e}`);
        return;
      }
      const ids = this.deps.db.prepare("SELECT id FROM figures WHERE dark_sha = ?").all(sha) as { id: string }[];
      for (const { id } of ids) this.update(id, {});
    });
  }

  /** Waits for previews already asked for (tests). */
  previewsSettled() {
    return this.previewQueue;
  }

  private sign(file: string, expires: number) {
    return createHmac("sha256", this.deps.config.signingSecret).update(`page:${file}:${expires}`).digest();
  }

  /** [file] is "<sha>.html" for a page or "<sha>.png" for a preview. */
  fileUrl(file: string, expires: number) {
    return `${this.deps.config.publicUrl}/v1/pages/${file}?exp=${expires}&sig=${this.sign(file, expires).toString("base64url")}`;
  }

  readFile(file: string, expires: number, sig: string): Buffer | null {
    if (!/^[0-9a-f]{64}\.(html|png)$/.test(file) || !(expires > this.deps.now())) return null;
    const expected = this.sign(file, expires);
    const given = Buffer.from(sig, "base64url");
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
    const path = join(this.options.pagesDir, file);
    return existsSync(path) ? readFileSync(path) : null;
  }

  // --- JSON ---

  toJson(f: FigureRow, queue = this.queueOrder()) {
    const position = f.status === "queued" ? queue.findIndex((q) => q.id === f.id) + 1 : 0;
    const expires = this.deps.now() + PAGE_URL_TTL_MS;
    const page = (sha: string | null, bytes: number | null) =>
      sha ? { url: this.fileUrl(`${sha}.html`, expires), sha256: sha, bytes, expiresAt: iso(expires) } : null;
    const succeeded = f.status === "succeeded";
    return {
      id: f.id,
      prompt: f.prompt,
      status: f.status,
      queuePosition: position || null,
      estimatedStartAt: position ? iso(this.estimateStart(position)) : null,
      stage: f.stage,
      message: f.message,
      progress: f.progress,
      title: f.title,
      summary: f.summary,
      conversationId: f.conversation_id,
      createdAt: iso(f.created_at),
      updatedAt: iso(f.updated_at),
      startedAt: iso(f.started_at),
      finishedAt: iso(f.finished_at),
      error: f.error_json ? JSON.parse(f.error_json) : null,
      pages: succeeded ? { dark: page(f.dark_sha, f.dark_bytes), light: page(f.light_sha, f.light_bytes) } : null,
      previewUrl: succeeded && f.dark_sha && existsSync(this.previewFile(f.dark_sha)) ? this.fileUrl(`${f.dark_sha}.png`, expires) : null,
    };
  }

  /** When the job at [position] should start: average job length times the rounds of workers ahead of it. */
  private estimateStart(position: number) {
    const recent = this.deps.db
      .prepare("SELECT finished_at - started_at AS ms FROM figures WHERE status = 'succeeded' AND started_at IS NOT NULL ORDER BY finished_at DESC LIMIT 20")
      .all() as { ms: number }[];
    const average = recent.length ? recent.reduce((s, r) => s + r.ms, 0) / recent.length : DEFAULT_JOB_MS;
    const rounds = Math.ceil(position / Math.max(1, this.options.workers));
    // The first round waits for the running jobs; assume they are about half done.
    return this.deps.now() + average * (rounds - 0.5);
  }

  capacity() {
    const queued = this.queueOrder();
    return {
      workers: this.options.workers,
      busy: this.running.size,
      idle: Math.max(0, this.options.workers - this.running.size),
      queueLength: queued.length,
      longestWaitEstimate: queued.length ? iso(this.estimateStart(queued.length)) : null,
    };
  }

  /** Calls [listener] on every change to figure [id]; null when it is deleted. */
  watch(id: string, listener: (id: string | null) => void) {
    this.updates.on(id, listener);
    return () => this.updates.off(id, listener);
  }
}

export function figureRoutes(app: FastifyInstance, deps: Deps, figures: Figures) {
  const own = (request: FastifyRequest) => {
    const f = figures.get((request.params as { id: string }).id);
    if (!f || f.user_id !== request.user.id) throw notFound("This figure");
    return f;
  };

  app.post("/figures", async (request, reply) => {
    const body = (request.body ?? {}) as { prompt?: unknown; theme?: unknown; conversationId?: unknown };
    if (typeof body.prompt !== "string") throw badRequest("Describe what the figure should show.");
    let conversationId: string | null = null;
    if (typeof body.conversationId === "string") {
      const c = deps.db.prepare("SELECT user_id FROM conversations WHERE id = ?").get(body.conversationId) as { user_id: string } | undefined;
      if (!c || c.user_id !== request.user.id) throw notFound("This chat");
      conversationId = body.conversationId;
    }
    const id = figures.create(request.user.id, body.prompt, conversationId, body.theme === "light" ? "light" : "dark");
    return reply.code(202).send(figures.toJson(figures.get(id)!));
  });

  app.get("/figures", async (request) => {
    const q = (request.query ?? {}) as { cursor?: string; limit?: string };
    const limit = Math.min(100, Math.max(1, Number(q.limit) || 50));
    const offset = Math.max(0, Number(q.cursor) || 0);
    const rows = deps.db
      .prepare("SELECT * FROM figures WHERE user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?")
      .all(request.user.id, limit + 1, offset) as unknown as FigureRow[];
    const queue = figures.queueOrder();
    return { items: rows.slice(0, limit).map((f) => figures.toJson(f, queue)), nextCursor: rows.length > limit ? String(offset + limit) : null };
  });

  app.get("/figures/:id", async (request) => figures.toJson(own(request)));

  app.post("/figures/:id/cancel", async (request) => {
    figures.cancel(own(request).id);
    return figures.toJson(own(request));
  });

  app.post("/figures/:id/retry", async (request) => {
    figures.retry(own(request).id);
    return figures.toJson(own(request));
  });

  app.delete("/figures/:id", async (request, reply) => {
    figures.delete(own(request).id);
    return reply.code(204).send();
  });

  app.get("/figures/:id/events", async (request, reply) => {
    const f = own(request);
    reply.hijack();
    const res = reply.raw;
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive", "X-Accel-Buffering": "no" });
    let seq = 0;
    let closed = false;
    let lastBody = "";
    const send = () => {
      const current = figures.get(f.id);
      if (!current) return close();
      const json = figures.toJson(current);
      // Signed URLs change every call; compare without them to skip repeats.
      const body = JSON.stringify({ ...json, pages: json.pages && Object.keys(json.pages), previewUrl: !!json.previewUrl, estimatedStartAt: json.estimatedStartAt?.slice(0, 16) });
      if (body !== lastBody) {
        lastBody = body;
        res.write(`id: ${++seq}\nevent: figure.update\ndata: ${JSON.stringify({ figure: json })}\n\n`);
      }
      if (!ACTIVE.includes(current.status)) close();
    };
    const close = () => {
      if (closed) return;
      closed = true;
      clearInterval(ping);
      unwatch();
      res.end();
    };
    const unwatch = figures.watch(f.id, () => send());
    const ping = setInterval(() => res.write("event: ping\ndata: {}\n\n"), 15_000);
    request.raw.on("close", close);
    send();
  });
}

/** Signed page and preview downloads; no session needed, the signature is the permission. */
export function pageRoutes(app: FastifyInstance, figures: Figures) {
  app.get("/pages/:file", async (request, reply) => {
    const file = (request.params as { file: string }).file;
    const q = (request.query ?? {}) as { exp?: string; sig?: string };
    const body = figures.readFile(file, Number(q.exp), q.sig ?? "");
    if (!body) throw notFound("This page");
    if (file.endsWith(".png")) {
      return reply.header("Content-Type", "image/png").header("Cache-Control", "private, max-age=3600").send(body);
    }
    return reply
      .header("Content-Type", "text/html; charset=utf-8")
      .header("Cache-Control", "private, max-age=3600")
      .header("Content-Security-Policy", "sandbox allow-scripts")
      .send(body);
  });
}
