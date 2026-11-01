import assert from "node:assert/strict";
import { test } from "node:test";
import { signIn, testServer } from "./helpers.ts";

test("email code sign-in creates the account and returns tokens", async () => {
  const server = await testServer();
  const session = await signIn(server, "Ada@Example.com");
  assert.equal(session.user.email, "ada@example.com");
  assert.equal(session.user.displayName, "ada");
  assert.equal(session.user.role, "user");
  const me = await server.app.inject({ method: "GET", url: "/v1/me", headers: session.headers });
  assert.equal(me.json().user.id, session.user.id);
});

test("a wrong code counts attempts and then locks", async () => {
  const server = await testServer({ VL_DEV_AUTH: "0", VL_SIGNING_SECRET: "s" });
  await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email: "a@b.co" } });
  const wrong = server.mail.get("a@b.co") === "000000" ? "111111" : "000000";
  for (let i = 0; i < 5; i++) {
    const res = await server.app.inject({ method: "POST", url: "/v1/auth/session", payload: { provider: "email", email: "a@b.co", code: wrong } });
    assert.equal(res.json().error.code, "invalid_code");
  }
  const locked = await server.app.inject({
    method: "POST", url: "/v1/auth/session", payload: { provider: "email", email: "a@b.co", code: server.mail.get("a@b.co") },
  });
  assert.match(locked.json().error.message, /Too many tries/);
});

test("the development code is refused without development auth", async () => {
  const server = await testServer({ VL_DEV_AUTH: "0", VL_SIGNING_SECRET: "s" });
  await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email: "a@b.co" } });
  if (server.mail.get("a@b.co") === "123456") return; // one-in-a-million real code
  const res = await server.app.inject({ method: "POST", url: "/v1/auth/session", payload: { provider: "email", email: "a@b.co", code: "123456" } });
  assert.equal(res.statusCode, 400);
});

test("asking for a new code within a minute is rate limited", async () => {
  const server = await testServer();
  await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email: "a@b.co" } });
  const again = await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email: "a@b.co" } });
  assert.equal(again.statusCode, 429);
  assert.ok(Number(again.headers["retry-after"]) > 0);
  server.clock.now += 61_000;
  const later = await server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email: "a@b.co" } });
  assert.equal(later.statusCode, 204);
});

test("refresh tokens rotate and access tokens expire", async () => {
  const server = await testServer();
  const session = await signIn(server);
  const refreshed = await server.app.inject({ method: "POST", url: "/v1/auth/refresh", payload: { refreshToken: session.refreshToken } });
  assert.equal(refreshed.statusCode, 200);
  const reused = await server.app.inject({ method: "POST", url: "/v1/auth/refresh", payload: { refreshToken: session.refreshToken } });
  assert.equal(reused.statusCode, 401);
  server.clock.now += 61 * 60 * 1000;
  const expired = await server.app.inject({ method: "GET", url: "/v1/me", headers: session.headers });
  assert.equal(expired.statusCode, 401);
});

test("google sign-in links to the email account and admin emails get the admin role", async () => {
  const server = await testServer({ VL_ADMIN_EMAILS: "boss@example.com" }, async (token) => ({ sub: `g-${token}`, email: "boss@example.com", name: "Boss" }));
  const byEmail = await signIn(server, "boss@example.com");
  const res = await server.app.inject({ method: "POST", url: "/v1/auth/session", payload: { provider: "google", idToken: "abc" } });
  assert.equal(res.statusCode, 200);
  assert.equal(res.json().user.id, byEmail.user.id);
  assert.equal(res.json().user.role, "admin");
});

test("google sign-in without a configured client is refused politely", async () => {
  const server = await testServer();
  const res = await server.app.inject({ method: "POST", url: "/v1/auth/session", payload: { provider: "google", idToken: "x" } });
  assert.equal(res.statusCode, 400);
});

test("deleting the account signs out everywhere", async () => {
  const server = await testServer();
  const session = await signIn(server);
  const del = await server.app.inject({ method: "DELETE", url: "/v1/me", headers: session.headers });
  assert.equal(del.statusCode, 202);
  assert.equal((await server.app.inject({ method: "GET", url: "/v1/me", headers: session.headers })).statusCode, 401);
  const refresh = await server.app.inject({ method: "POST", url: "/v1/auth/refresh", payload: { refreshToken: session.refreshToken } });
  assert.equal(refresh.statusCode, 401);
});
