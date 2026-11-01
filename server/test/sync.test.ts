import assert from "node:assert/strict";
import { test } from "node:test";
import { signIn, testServer, type TestServer } from "./helpers.ts";

const sync = async (server: TestServer, headers: Record<string, string>, since?: string) =>
  (await server.app.inject({ method: "GET", url: since ? `/v1/sync?since=${since}` : "/v1/sync", headers })).json();

test("first sync returns everything, later syncs only changes and deletions", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const c1 = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers, payload: {} })).json();
  const c2 = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers, payload: {} })).json();
  const fig = (await server.app.inject({ method: "POST", url: "/v1/figures", headers, payload: { prompt: "gears" } })).json();
  await server.app.inject({ method: "GET", url: `/v1/figures/${fig.id}/events`, headers });

  const first = await sync(server, headers);
  assert.deepEqual(first.conversations.map((c: { id: string }) => c.id).sort(), [c1.id, c2.id].sort());
  assert.equal(first.figures[0].id, fig.id);
  assert.equal(first.figures[0].status, "succeeded");
  assert.ok(first.figures[0].pages.dark.url);

  server.clock.now += 1000;
  await server.app.inject({ method: "PATCH", url: `/v1/conversations/${c1.id}`, headers, payload: { title: "Renamed" } });
  await server.app.inject({ method: "DELETE", url: `/v1/conversations/${c2.id}`, headers });
  await server.app.inject({ method: "DELETE", url: `/v1/figures/${fig.id}`, headers });
  const changedAt = server.clock.now;
  server.clock.now += 5;

  const next = await sync(server, headers, first.cursor);
  assert.deepEqual(next.conversations, [{ id: c1.id, title: "Renamed", updatedAt: new Date(changedAt).toISOString() }]);
  assert.deepEqual(next.deletedConversationIds, [c2.id]);
  assert.deepEqual(next.figures, []);
  assert.deepEqual(next.deletedFigureIds, [fig.id]);

  server.clock.now += 1000;
  const quiet = await sync(server, headers, next.cursor);
  assert.deepEqual([quiet.conversations, quiet.deletedConversationIds, quiet.figures, quiet.deletedFigureIds], [[], [], [], []]);
});

test("sync only shows the caller's own data", async () => {
  const server = await testServer();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  const c = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers: a.headers, payload: {} })).json();
  await server.app.inject({ method: "DELETE", url: `/v1/conversations/${c.id}`, headers: a.headers });
  const theirs = await sync(server, b.headers);
  assert.deepEqual([theirs.conversations, theirs.deletedConversationIds], [[], []]);
});

test("a bad cursor is a 400", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const res = await server.app.inject({ method: "GET", url: "/v1/sync?since=yesterday", headers });
  assert.equal(res.statusCode, 400);
});
