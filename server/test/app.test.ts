import assert from "node:assert/strict";
import { test } from "node:test";
import { testServer } from "./helpers.ts";

test("health answers under /v1", async () => {
  const { app } = await testServer();
  const res = await app.inject({ method: "GET", url: "/v1/health" });
  assert.equal(res.statusCode, 200);
  assert.deepEqual(res.json(), { ok: true });
});

test("unknown routes use the error body", async () => {
  const { app } = await testServer();
  const res = await app.inject({ method: "GET", url: "/v1/nope" });
  assert.equal(res.statusCode, 404);
  assert.equal(res.json().error.code, "not_found");
});

test("an old ui library gets 426", async () => {
  const { app } = await testServer({ VL_MIN_UI_LIBRARY: "1.2.0" });
  const res = await app.inject({ method: "GET", url: "/v1/health", headers: { "x-ui-library-version": "1.0.0" } });
  assert.equal(res.statusCode, 426);
  assert.equal(res.json().error.code, "upgrade_required");
});
