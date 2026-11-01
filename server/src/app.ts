import Fastify, { type FastifyInstance } from "fastify";
import type { Config } from "./config.ts";
import type { Db } from "./db.ts";
import { ApiError } from "./errors.ts";
import { compareVersions } from "./util.ts";

export interface Deps {
  config: Config;
  db: Db;
  /** Current time; tests replace it. */
  now: () => number;
}

/** Builds the HTTP app. Every route lives under /v1 (section 2). */
export async function buildApp(deps: Deps, options: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({ logger: options.logger ?? false, bodyLimit: 1_000_000 });

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
    },
    { prefix: "/v1" },
  );

  return app;
}
