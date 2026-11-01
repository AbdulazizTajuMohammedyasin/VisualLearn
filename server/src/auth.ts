import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import type { FastifyInstance, FastifyRequest } from "fastify";
import { OAuth2Client } from "google-auth-library";
import type { Deps } from "./app.ts";
import { transaction } from "./db.ts";
import { ApiError, badRequest, rateLimited, unauthenticated } from "./errors.ts";
import { iso, newId, randomToken, sha256 } from "./util.ts";

const ACCESS_TTL_MS = 60 * 60 * 1000;
const REFRESH_TTL_MS = 60 * 24 * 60 * 60 * 1000;
const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_COOLDOWN_MS = 60 * 1000;
const CODE_MAX_ATTEMPTS = 5;
const DEV_CODE = "123456";

export interface UserRow {
  id: string;
  email: string | null;
  google_sub: string | null;
  display_name: string;
  avatar_url: string | null;
  role: "user" | "admin";
  created_at: number;
}

/** Sends sign-in codes. Production needs a real mail provider; development logs them. */
export interface Mailer {
  sendSignInCode(email: string, code: string): Promise<void>;
}

/** Checks a Google ID token and returns its claims; replaceable in tests. */
export type GoogleVerifier = (idToken: string) => Promise<{ sub: string; email?: string; name?: string; picture?: string }>;

export function googleVerifier(clientId: string): GoogleVerifier | null {
  if (!clientId) return null;
  const client = new OAuth2Client();
  return async (idToken) => {
    const ticket = await client.verifyIdToken({ idToken, audience: clientId });
    const p = ticket.getPayload();
    if (!p?.sub) throw new Error("no subject");
    return { sub: p.sub, email: p.email_verified ? p.email : undefined, name: p.name, picture: p.picture };
  };
}

export function publicUser(u: UserRow) {
  return { id: u.id, displayName: u.display_name, email: u.email, avatarUrl: u.avatar_url, role: u.role };
}

declare module "fastify" {
  interface FastifyRequest {
    user: UserRow;
  }
}

/** Access tokens: `<payload>.<hmac>`, payload `{ sub, exp }`. Stateless, one hour. */
function signAccess(secret: string, userId: string, exp: number) {
  const payload = Buffer.from(JSON.stringify({ sub: userId, exp })).toString("base64url");
  return `${payload}.${createHmac("sha256", secret).update(payload).digest("base64url")}`;
}

function verifyAccess(secret: string, token: string, now: number): string | null {
  const [payload, mac] = token.split(".");
  if (!payload || !mac) return null;
  const expected = createHmac("sha256", secret).update(payload).digest();
  const given = Buffer.from(mac, "base64url");
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return null;
  try {
    const { sub, exp } = JSON.parse(Buffer.from(payload, "base64url").toString());
    return typeof sub === "string" && typeof exp === "number" && exp > now ? sub : null;
  } catch {
    return null;
  }
}

export interface AuthOptions {
  mailer: Mailer;
  google: GoogleVerifier | null;
}

export function authRoutes(app: FastifyInstance, deps: Deps, options: AuthOptions) {
  const { db, config, now } = deps;

  const session = (user: UserRow) => {
    const refreshToken = randomToken();
    db.prepare("INSERT INTO sessions (refresh_hash, user_id, expires_at) VALUES (?, ?, ?)").run(
      sha256(refreshToken), user.id, now() + REFRESH_TTL_MS,
    );
    const exp = now() + ACCESS_TTL_MS;
    return { accessToken: signAccess(config.signingSecret, user.id, exp), accessTokenExpiresAt: iso(exp), refreshToken, user: publicUser(user) };
  };

  const roleFor = (email: string | null) => (email && config.adminEmails.includes(email.toLowerCase()) ? "admin" : "user");

  const findOrCreate = (claims: { email: string | null; googleSub?: string; name?: string; picture?: string }): UserRow =>
    transaction(db, () => {
      let user =
        (claims.googleSub && (db.prepare("SELECT * FROM users WHERE google_sub = ?").get(claims.googleSub) as UserRow | undefined)) ||
        (claims.email && (db.prepare("SELECT * FROM users WHERE email = ?").get(claims.email) as UserRow | undefined)) ||
        undefined;
      if (!user) {
        const id = newId("usr");
        const name = claims.name || claims.email?.split("@")[0] || "Learner";
        db.prepare("INSERT INTO users (id, email, google_sub, display_name, avatar_url, role, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)").run(
          id, claims.email, claims.googleSub ?? null, name, claims.picture ?? null, roleFor(claims.email), now(),
        );
        user = db.prepare("SELECT * FROM users WHERE id = ?").get(id) as unknown as UserRow;
      } else {
        // Link Google to an account first made by email, and keep admin rights in step with config.
        if (claims.googleSub && !user.google_sub) db.prepare("UPDATE users SET google_sub = ? WHERE id = ?").run(claims.googleSub, user.id);
        if (roleFor(user.email) === "admin" && user.role !== "admin") db.prepare("UPDATE users SET role = 'admin' WHERE id = ?").run(user.id);
        user = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id) as unknown as UserRow;
      }
      return user;
    });

  const normalEmail = (value: unknown) => {
    const email = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) || email.length > 254) throw badRequest("Enter a valid email address.");
    return email;
  };

  app.post("/auth/email/start", async (request, reply) => {
    const email = normalEmail((request.body as { email?: unknown } | undefined)?.email);
    const existing = db.prepare("SELECT sent_at FROM email_codes WHERE email = ?").get(email) as { sent_at: number } | undefined;
    if (existing && now() - existing.sent_at < CODE_COOLDOWN_MS) {
      throw rateLimited(Math.ceil((CODE_COOLDOWN_MS - (now() - existing.sent_at)) / 1000), "Wait a minute before asking for another code.");
    }
    const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
    db.prepare(
      `INSERT INTO email_codes (email, code_hash, expires_at, attempts, sent_at) VALUES (?, ?, ?, 0, ?)
       ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0, sent_at = excluded.sent_at`,
    ).run(email, sha256(`${email}:${code}`), now() + CODE_TTL_MS, now());
    await options.mailer.sendSignInCode(email, code);
    return reply.code(204).send();
  });

  app.post("/auth/session", async (request) => {
    const body = (request.body ?? {}) as Record<string, unknown>;
    if (body.provider === "email") {
      const email = normalEmail(body.email);
      const code = typeof body.code === "string" ? body.code.trim() : "";
      if (!(config.devAuth && code === DEV_CODE)) checkCode(email, code);
      return session(findOrCreate({ email }));
    }
    if (body.provider === "google") {
      if (!options.google) throw badRequest("Google sign-in isn't set up on this server. Use email instead.");
      if (typeof body.idToken !== "string") throw badRequest("Missing Google token.");
      const claims = await options.google(body.idToken).catch(() => {
        throw new ApiError(401, "unauthenticated", "Google sign-in didn't work. Try again.");
      });
      return session(findOrCreate({ email: claims.email?.toLowerCase() ?? null, googleSub: claims.sub, name: claims.name, picture: claims.picture }));
    }
    throw badRequest("Unknown sign-in method.");
  });

  function checkCode(email: string, code: string) {
    const row = db.prepare("SELECT * FROM email_codes WHERE email = ?").get(email) as
      | { code_hash: string; expires_at: number; attempts: number }
      | undefined;
    if (!row || row.expires_at < now()) throw new ApiError(400, "invalid_code", "That code has expired. Ask for a new one.");
    if (row.attempts >= CODE_MAX_ATTEMPTS) throw new ApiError(400, "invalid_code", "Too many tries. Ask for a new code.");
    if (row.code_hash !== sha256(`${email}:${code}`)) {
      db.prepare("UPDATE email_codes SET attempts = attempts + 1 WHERE email = ?").run(email);
      throw new ApiError(400, "invalid_code", "That code isn't right.");
    }
    db.prepare("DELETE FROM email_codes WHERE email = ?").run(email);
  }

  app.post("/auth/refresh", async (request) => {
    const token = (request.body as { refreshToken?: unknown } | undefined)?.refreshToken;
    if (typeof token !== "string") throw unauthenticated();
    // Rotation: the old refresh token stops working as soon as it is used.
    const row = db.prepare("DELETE FROM sessions WHERE refresh_hash = ? RETURNING user_id, expires_at").get(sha256(token)) as
      | { user_id: string; expires_at: number }
      | undefined;
    if (!row || row.expires_at < now()) throw unauthenticated();
    const user = db.prepare("SELECT * FROM users WHERE id = ?").get(row.user_id) as UserRow | undefined;
    if (!user) throw unauthenticated();
    return session(user);
  });

  app.delete("/auth/session", async (request, reply) => {
    const token = (request.body as { refreshToken?: unknown } | undefined)?.refreshToken;
    if (typeof token === "string") db.prepare("DELETE FROM sessions WHERE refresh_hash = ?").run(sha256(token));
    return reply.code(204).send();
  });
}

/** Resolves the signed-in user for routes registered after it; 401 otherwise. */
export function requireUser(app: FastifyInstance, deps: Deps) {
  app.decorateRequest("user", null as unknown as UserRow);
  app.addHook("onRequest", async (request: FastifyRequest) => {
    const header = request.headers.authorization;
    const token = header?.startsWith("Bearer ") ? header.slice(7) : "";
    const userId = token && verifyAccess(deps.config.signingSecret, token, deps.now());
    if (!userId) throw unauthenticated();
    const user = deps.db.prepare("SELECT * FROM users WHERE id = ?").get(userId) as UserRow | undefined;
    if (!user) throw unauthenticated();
    request.user = user;
  });
}

export const consoleMailer = (log: (message: string) => void): Mailer => ({
  async sendSignInCode(email, code) {
    log(`Sign-in code for ${email}: ${code}`);
  },
});
