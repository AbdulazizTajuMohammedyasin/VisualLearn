import assert from "node:assert/strict";
import { test } from "node:test";
import { signIn, testServer } from "./helpers.ts";

async function adminServer() {
  const server = await testServer({ VL_ADMIN_EMAILS: "admin@example.com" });
  const admin = await signIn(server, "admin@example.com");
  const user = await signIn(server, "user@example.com");
  return { server, admin, user };
}

test("admin endpoints are refused to ordinary users", async () => {
  const { server, user } = await adminServer();
  const res = await server.app.inject({ method: "GET", url: "/v1/admin/routing", headers: user.headers });
  assert.equal(res.statusCode, 403);
});

test("provider keys are write-only and survive an update without one", async () => {
  const { server, admin } = await adminServer();
  const put = await server.app.inject({
    method: "PUT", url: "/v1/admin/providers/anthropic", headers: admin.headers,
    payload: { kind: "api", npm: "@ai-sdk/anthropic", apiKey: "sk-secret", models: ["claude-opus-5-5"] },
  });
  assert.equal(put.statusCode, 200, put.body);
  assert.equal(put.json().apiKey, "••••");
  await server.app.inject({
    method: "PUT", url: "/v1/admin/providers/anthropic", headers: admin.headers,
    payload: { kind: "api", npm: "@ai-sdk/anthropic", apiKey: "••••", models: ["claude-opus-5-5", "claude-sonnet-5-5"] },
  });
  assert.equal(server.deps.models.provider("anthropic")!.apiKey, "sk-secret");
  const list = (await server.app.inject({ method: "GET", url: "/v1/admin/providers", headers: admin.headers })).body;
  assert.ok(!list.includes("sk-secret"));
});

test("routing must name configured models, and a used provider can't be deleted", async () => {
  const { server, admin } = await adminServer();
  await server.app.inject({
    method: "PUT", url: "/v1/admin/providers/lab-gpu", headers: admin.headers,
    payload: { kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: "http://10.0.0.5:8000/v1", models: ["qwen"] },
  });
  const bad = await server.app.inject({
    method: "PUT", url: "/v1/admin/routing", headers: admin.headers,
    payload: { chat: { model: "lab-gpu/llama" }, figures: { model: "dev/canned" } },
  });
  assert.equal(bad.statusCode, 400);
  const good = await server.app.inject({
    method: "PUT", url: "/v1/admin/routing", headers: admin.headers,
    payload: { chat: { model: "lab-gpu/qwen", fallback: "dev/canned" }, figures: { model: "dev/canned", fallback: null } },
  });
  assert.equal(good.statusCode, 200);
  assert.deepEqual(server.deps.models.routing().chat, { model: "lab-gpu/qwen", fallback: "dev/canned" });
  const del = await server.app.inject({ method: "DELETE", url: "/v1/admin/providers/lab-gpu", headers: admin.headers });
  assert.equal(del.statusCode, 409);
});

test("a local provider needs a base URL", async () => {
  const { server, admin } = await adminServer();
  const res = await server.app.inject({
    method: "PUT", url: "/v1/admin/providers/box", headers: admin.headers,
    payload: { kind: "local", npm: "@ai-sdk/openai-compatible", models: ["m"] },
  });
  assert.equal(res.statusCode, 400);
});

test("admins can find users, delete their content, and the deletion reaches sync", async () => {
  const { server, admin, user } = await adminServer();
  const conv = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers: user.headers, payload: {} })).json();
  const found = (await server.app.inject({ method: "GET", url: "/v1/admin/users?query=user@", headers: admin.headers })).json();
  assert.deepEqual(found.items.map((u: { email: string }) => u.email), ["user@example.com"]);
  const profile = (await server.app.inject({ method: "GET", url: `/v1/admin/users/${user.user.id}`, headers: admin.headers })).json();
  assert.equal(profile.conversations, 1);

  assert.equal((await server.app.inject({ method: "DELETE", url: `/v1/admin/conversations/${conv.id}`, headers: admin.headers })).statusCode, 204);
  const sync = (await server.app.inject({ method: "GET", url: "/v1/sync", headers: user.headers })).json();
  assert.deepEqual(sync.deletedConversationIds, [conv.id]);

  assert.equal((await server.app.inject({ method: "DELETE", url: `/v1/admin/users/${user.user.id}`, headers: admin.headers })).statusCode, 204);
  assert.equal((await server.app.inject({ method: "GET", url: "/v1/me", headers: user.headers })).statusCode, 401);
  assert.equal((await server.app.inject({ method: "DELETE", url: `/v1/admin/users/${admin.user.id}`, headers: admin.headers })).statusCode, 409);
});

test("capacity reports workers and the queue, and admin calls are audited", async () => {
  const { server, admin } = await adminServer();
  const cap = (await server.app.inject({ method: "GET", url: "/v1/admin/capacity", headers: admin.headers })).json();
  assert.equal(cap.figures.workers, 1);
  assert.equal(cap.figures.queueLength, 0);
  assert.equal(cap.chat.activeReplies, 0);
  const audit = server.deps.db.prepare("SELECT method, path, status FROM audit_log").all();
  assert.deepEqual(audit.map((r) => ({ ...r })), [{ method: "GET", path: "/v1/admin/capacity", status: 200 }]);
});
