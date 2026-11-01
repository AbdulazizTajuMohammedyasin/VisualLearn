import Fastify, { type FastifyInstance } from "fastify";
import { authRoutes, publicUser, requireUser, type GoogleVerifier, type Mailer } from "./auth.ts";
import { ChatEngine, chatRoutes } from "./chat.ts";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { ApiError } from "./errors.ts";
import { figureRoutes, pageRoutes, type Figures } from "./figures.ts";
import { syncRoutes } from "./sync.ts";
import { adminRoutes } from "./admin.ts";
import type { ModelRegistry } from "./models.ts";
import { compareVersions } from "./util.ts";

/** Deletes an account and everything it owns (section 3, required by Google Play). */
export function deleteUser(deps: Deps, userId: string) {
  // Stored pages are files; the rest goes with the user row through ON DELETE CASCADE.
  deps.figures?.deleteForUser(userId);
  deps.db.prepare("DELETE FROM users WHERE id = ?").run(userId);
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
export async function buildApp(deps: Deps, options: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger: options.logger ?? false, bodyLimit: 1_000_000 });
  const chat = new ChatEngine(deps);

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      return reply.code(error.status).headers(error.headers).send(error.body());
    }
    const fastifyError = error as { statusCode?: number; validation?: unknown; message: string };
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

      v1.register(async (auth) => authRoutes(auth, deps, deps));

      // Everything below needs a signed-in user.
      v1.register(async (authed) => {
        requireUser(authed, deps);

        authed.get("/me", async (request) => ({ user: publicUser(request.user) }));

        authed.delete("/me", async (request, reply) => {
          deleteUser(deps, request.user.id);
          return reply.code(202).send();
        });

        chatRoutes(authed, deps, chat);
        if (deps.figures) figureRoutes(authed, deps, deps.figures);
        syncRoutes(authed, deps, deps.figures);
        authed.register(async (admin) => adminRoutes(admin, deps, chat));
      });
    },
    { prefix: "/v1" },
  );

  return app;
}
