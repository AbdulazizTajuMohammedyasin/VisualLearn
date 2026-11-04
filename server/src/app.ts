import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import Fastify, { type FastifyInstance } from "fastify";
import { authRoutes, publicUser, requireUser, type GoogleVerifier, type Mailer } from "./auth.ts";
import { ChatEngine, chatRoutes } from "./chat.ts";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { documentRoutes, Documents } from "./documents.ts";
import { learnRoutes, type LearnPool } from "./learn.ts";
import { ApiError } from "./errors.ts";
import { figureRoutes, pageRoutes, type Figures } from "./figures.ts";
import { syncRoutes } from "./sync.ts";
import { rateLimits } from "./ratelimit.ts";
import { adminRoutes } from "./admin.ts";
import type { ModelRegistry } from "./models.ts";
import { compareVersions } from "./util.ts";

/** Deletes an account and everything it owns (section 3, required by Google Play). */
export function deleteUser(deps: Deps, userId: string) {
  // Stored pages are files; the rest goes with the user row through ON DELETE CASCADE.
  deps.figures?.deleteForUser(userId);
  deps.db.prepare("DELETE FROM users WHERE id = ?").run(userId);
  deps.documents?.sweep();
  void deps.learn?.forget(userId).catch((e) => deps.log(`deleting lessons for ${userId}: ${(e as Error).message}`));
}

export interface Deps {
  config: Config;
  db: Db;
  /** Current time; tests replace it. */
  now: () => number;
  mailer: Mailer;
  google: GoogleVerifier | null;
  models: ModelRegistry;
  /** Figure jobs; null runs chat without figures. */
  figures: Figures | null;
  /** Attached PDFs; buildApp creates them under the data folder when not given. */
  documents?: Documents;
  /** Each learner's LearnHarness process; absent when lessons are off (no VL_HARNESS_DIR). */
  learn?: LearnPool;
  /** The model's instructions, including the OpenUI component library (ui-library/dist/system-prompt.md). */
  chatSystemPrompt: string;
  /** The OpenUI library version programs are written for. */
  uiLibraryVersion: string;
  log: (message: string) => void;
}

export interface FigureService {
  /** Queues a figure job and returns its id. */
  create(userId: string, prompt: string, conversationId: string | null): string;
  /** Stops and removes everything an account owns, before the account is deleted. */
  deleteForUser(userId: string): void;
}

/** Builds the HTTP app. Every route lives under /v1 (section 2). */
export async function buildApp(deps: Deps, options: { logger?: boolean; env?: NodeJS.ProcessEnv } = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger: options.logger ?? false, bodyLimit: 1_000_000 });
  deps.documents ??= new Documents(
    deps,
    deps.config.dataDir === ":memory:" ? mkdtempSync(join(tmpdir(), "vl-docs-")) : join(deps.config.dataDir, "documents"),
  );
  const documents = deps.documents;
  const chat = new ChatEngine(deps);
  // The spec keeps finished replies' events for at least 10 minutes; a day is generous.
  const prune = setInterval(() => chat.pruneEvents(24 * 60 * 60 * 1000), 60 * 60 * 1000);
  prune.unref();
  app.addHook("onClose", async () => {
    clearInterval(prune);
    await deps.learn?.close();
  });
  const limits = rateLimits(deps.now, options.env);

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      return reply.code(error.status).headers(error.headers).send(error.body());
    }
    const fastifyError = error as { statusCode?: number; validation?: unknown; message: string };
    if (fastifyError.statusCode === 413) {
      return reply.code(413).send(new ApiError(413, "too_large", "That file is too big. The limit is 25 MB.").body());
    }
    if (fastifyError.statusCode === 415) {
      return reply.code(415).send(new ApiError(415, "unsupported_type", "Only PDF files can be attached.").body());
    }
    if (fastifyError.validation || fastifyError.statusCode === 400) {
      return reply.code(400).send(new ApiError(400, "invalid_request", "The request wasn't valid.").body());
    }
    request.log.error(error);
    return reply.code(500).send(new ApiError(500, "internal", "Something went wrong on our side. Try again.").body());
  });

  app.setNotFoundHandler((_request, reply) => {
    reply.code(404).send(new ApiError(404, "not_found", "There's nothing here.").body());
  });

  await app.register(
    async (v1) => {
      // Apps whose interactive-component library is too old can't render what the server sends.
      v1.addHook("onRequest", async (request) => {
        const ui = request.headers["x-ui-library-version"];
        if (typeof ui === "string" && compareVersions(ui, deps.config.minUiLibraryVersion) < 0) {
          throw new ApiError(426, "upgrade_required", "Please update VisualLearn to keep using it.");
        }
      });

      v1.get("/health", async () => ({ ok: true }));
      if (deps.figures) pageRoutes(v1, deps.figures);

      v1.register(async (auth) => {
        auth.addHook("onRequest", async (request) => limits.auth.hit(`ip:${request.ip}`));
        authRoutes(auth, deps, deps);
      });

      // Everything below needs a signed-in user.
      v1.register(async (authed) => {
        requireUser(authed, deps);
        authed.addHook("preHandler", async (request) => {
          limits.requests.hit(request.user.id);
          const url = request.routeOptions.url ?? "";
          const work = ["/v1/figures", "/v1/conversations/:id/messages", "/v1/conversations/:id/documents"];
          if (request.method === "POST" && work.includes(url)) limits.work.hit(request.user.id);
        });

        authed.get("/me", async (request) => ({ user: publicUser(request.user) }));

        authed.delete("/me", async (request, reply) => {
          deleteUser(deps, request.user.id);
          return reply.code(202).send();
        });

        chatRoutes(authed, deps, chat);
        documentRoutes(authed, deps, documents);
        if (deps.learn) learnRoutes(authed, deps, deps.learn, limits.work);
        if (deps.figures) figureRoutes(authed, deps, deps.figures);
        syncRoutes(authed, deps, deps.figures);
        authed.register(async (admin) => adminRoutes(admin, deps, chat));
      });
    },
    { prefix: "/v1" },
  );

  return app;
}
