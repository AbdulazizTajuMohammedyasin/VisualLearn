import assert from "node:assert/strict";
import { test } from "node:test";
import { signIn, testServer } from "./helpers.ts";

test("sending messages too fast is rate limited, then allowed again a minute later", async () => {
  const server = await testServer({ VL_RATE_WORK_PER_MIN: "2" });
  const { headers } = await signIn(server);
  const create = () => server.app.inject({ method: "POST", url: "/v1/figures", headers, payload: { prompt: "gears" } });
  assert.equal((await create()).statusCode, 202);
  assert.equal((await create()).statusCode, 202);
  const limited = await create();
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.json().error.code, "rate_limited");
  assert.ok(Number(limited.headers["retry-after"]) >= 1);
  // Reading is not "work" and stays allowed.
  assert.equal((await server.app.inject({ method: "GET", url: "/v1/figures", headers })).statusCode, 200);
  server.clock.now += 60_000;
  assert.equal((await create()).statusCode, 202);
});

test("sign-in attempts are limited per client address", async () => {
  const server = await testServer({ VL_RATE_AUTH_PER_MIN: "3" });
  const start = (email: string) => server.app.inject({ method: "POST", url: "/v1/auth/email/start", payload: { email } });
  for (const e of ["a@x.io", "b@x.io", "c@x.io"]) assert.equal((await start(e)).statusCode, 204);
  assert.equal((await start("d@x.io")).statusCode, 429);
});
