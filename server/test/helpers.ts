import type { FastifyInstance } from "fastify";
import { buildApp, type Deps } from "../src/app.ts";
import { loadConfig } from "../src/config.ts";
import { openDb } from "../src/db.ts";

export interface TestServer {
  app: FastifyInstance;
  deps: Deps;
  clock: { now: number };
}

/** A server on an in-memory database with development auth and a controllable clock. */
export async function testServer(env: Record<string, string> = {}): Promise<TestServer> {
  const clock = { now: Date.parse("2026-11-01T12:00:00Z") };
  const config = loadConfig({ VL_DEV_AUTH: "1", VL_DATA_DIR: ":memory:", ...env });
  const deps: Deps = { config, db: openDb(":memory:"), now: () => clock.now };
  const app = await buildApp(deps);
  return { app, deps, clock };
}
