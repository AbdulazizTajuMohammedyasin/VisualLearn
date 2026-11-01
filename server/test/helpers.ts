import type { FastifyInstance } from "fastify";
import type { GoogleVerifier } from "../src/auth.ts";
import { buildApp, type Deps } from "../src/app.ts";
import { loadConfig } from "../src/config.ts";
import { openDb } from "../src/db.ts";
import { ModelRegistry } from "../src/models.ts";
import { chatSystemPrompt } from "../src/prompt.ts";

export interface TestServer {
  app: FastifyInstance;
  deps: Deps;
  clock: { now: number };
  /** Codes the mailer "sent", by email. */
  mail: Map<string, string>;
}

/** A server on an in-memory database with development auth and a controllable clock. */
export async function testServer(env: Record<string, string> = {}, google: GoogleVerifier | null = null): Promise<TestServer> {
  const clock = { now: Date.parse("2026-11-01T12:00:00Z") };
  const mail = new Map<string, string>();
  const config = loadConfig({ VL_DEV_AUTH: "1", VL_DATA_DIR: ":memory:", ...env });
  const db = openDb(":memory:");
  const deps: Deps = {
    config,
    db,
    now: () => clock.now,
    mailer: { sendSignInCode: async (email, code) => void mail.set(email, code) },
    google,
    models: new ModelRegistry(db),
    figures: null,
    chatSystemPrompt: chatSystemPrompt(null),
    uiLibraryVersion: "1.0.0",
    log: () => {},
  };
  const app = await buildApp(deps);
  return { app, deps, clock, mail };
}

/** Signs in through the email code flow and returns the session plus ready-made auth headers. */
export async function signIn(server: TestServer, email = "learner@example.com") {
  await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email } });
  const res = await server.app.inject({
    method: "POST",
    url: "/v1/auth/session",
    payload: { provider: "email", email, code: server.mail.get(email.toLowerCase()) },
  });
  if (res.statusCode !== 200) throw new Error(res.body);
  const session = res.json();
  return { ...session, headers: { authorization: `Bearer ${session.accessToken}` } };
}
