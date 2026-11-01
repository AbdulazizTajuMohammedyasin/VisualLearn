import assert from "node:assert/strict";
import { test } from "node:test";
import { signIn, testServer, type TestServer } from "./helpers.ts";

interface SseEvent {
  id: number;
  event: string;
  data: any;
}

function parseSse(body: string): SseEvent[] {
  return body
    .split("\n\n")
    .filter((block) => block.includes("event:"))
    .map((block) => {
      const field = (name: string) => block.split("\n").find((l) => l.startsWith(`${name}:`))?.slice(name.length + 1).trim();
      return { id: Number(field("id")), event: field("event")!, data: JSON.parse(field("data") ?? "{}") };
    })
    .filter((e) => e.event !== "ping");
}

async function send(server: TestServer, headers: Record<string, string>, text: string, mode = "auto") {
  const conv = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers, payload: {} })).json();
  const res = await server.app.inject({ method: "POST", url: `/v1/conversations/${conv.id}/messages`, headers, payload: { text, mode, uiAction: null } });
  assert.equal(res.statusCode, 200, res.body);
  return { conv, ...res.json() };
}

async function events(server: TestServer, headers: Record<string, string>, messageId: string, lastEventId?: number) {
  const res = await server.app.inject({
    method: "GET",
    url: `/v1/messages/${messageId}/events`,
    headers: lastEventId ? { ...headers, "last-event-id": String(lastEventId) } : headers,
  });
  assert.equal(res.headers["content-type"], "text/event-stream");
  return parseSse(res.body);
}

test("a reply streams as text deltas and ends with the stored message", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { conv, userMessage, assistantMessage } = await send(server, headers, "How does a lock work?");
  assert.equal(userMessage.parts[0].text, "How does a lock work?");
  assert.equal(assistantMessage.status, "streaming");

  const stream = await events(server, headers, assistantMessage.id);
  assert.equal(stream[0].event, "part.start");
  assert.deepEqual(stream[0].data, { partIndex: 0, type: "text", text: "" });
  const text = stream.filter((e) => e.event === "text.delta").map((e) => e.data.text).join("");
  const done = stream.at(-1)!;
  assert.equal(done.event, "message.done");
  assert.equal(done.data.message.status, "complete");
  assert.equal(done.data.message.parts[0].text, text);

  const full = (await server.app.inject({ method: "GET", url: `/v1/conversations/${conv.id}`, headers })).json();
  assert.equal(full.title, "How does a lock work?");
  assert.equal(full.messages.length, 2);
  assert.equal(full.messages[1].status, "complete");
});

test("an openui block becomes a ui part", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { assistantMessage } = await send(server, headers, "Quiz me on zippers");
  const stream = await events(server, headers, assistantMessage.id);
  const parts = stream.at(-1)!.data.message.parts;
  assert.deepEqual(parts.map((p: { type: string }) => p.type), ["text", "ui", "text"]);
  assert.match(parts[1].program, /^root = Quiz\("Zippers"/);
  assert.equal(parts[1].libraryVersion, "1.0.0");
  assert.ok(stream.some((e) => e.event === "ui.delta"));
});

test("reconnecting with Last-Event-ID replays only what was missed", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { assistantMessage } = await send(server, headers, "Hello");
  const all = await events(server, headers, assistantMessage.id);
  const rest = await events(server, headers, assistantMessage.id, 3);
  assert.deepEqual(rest, all.filter((e) => e.id > 3));
});

test("other users can't see or delete a conversation", async () => {
  const server = await testServer();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  const { conv, assistantMessage } = await send(server, a.headers, "Mine");
  for (const [method, url] of [["GET", `/v1/conversations/${conv.id}`], ["DELETE", `/v1/conversations/${conv.id}`], ["GET", `/v1/messages/${assistantMessage.id}/events`]] as const) {
    assert.equal((await server.app.inject({ method, url, headers: b.headers })).statusCode, 404, url);
  }
  const list = (await server.app.inject({ method: "GET", url: "/v1/conversations", headers: b.headers })).json();
  assert.deepEqual(list.items, []);
});

test("deleting a conversation removes it and records the deletion for sync", async () => {
  const server = await testServer();
  const { headers, user } = await signIn(server);
  const { conv, assistantMessage } = await send(server, headers, "Hello");
  await events(server, headers, assistantMessage.id);
  assert.equal((await server.app.inject({ method: "DELETE", url: `/v1/conversations/${conv.id}`, headers })).statusCode, 204);
  assert.equal((await server.app.inject({ method: "GET", url: `/v1/conversations/${conv.id}`, headers })).statusCode, 404);
  const row = server.deps.db.prepare("SELECT kind, item_id FROM deletions WHERE user_id = ?").get(user.id);
  assert.deepEqual({ ...row }, { kind: "conversation", item_id: conv.id });
});

test("stopping a reply keeps what it had and completes it", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { assistantMessage } = await send(server, headers, "Tell me everything");
  await new Promise((r) => setTimeout(r, 60));
  assert.equal((await server.app.inject({ method: "POST", url: `/v1/messages/${assistantMessage.id}/stop`, headers })).statusCode, 204);
  const stream = await events(server, headers, assistantMessage.id);
  const done = stream.at(-1)!.data.message;
  assert.equal(done.status, "complete");
  assert.ok(done.parts[0].text.length > 0);
  assert.ok(!done.parts[0].text.includes("quiz me"), "the reply should have been cut short");
});

test("an unreachable primary model falls back", async () => {
  const server = await testServer();
  server.deps.models.saveProvider({ id: "broken", kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: "http://127.0.0.1:9/v1", apiKey: null, models: ["x"] });
  server.deps.models.saveRouting({ chat: { model: "broken/x", fallback: "dev/canned" }, figures: { model: "dev/canned", fallback: null } });
  const { headers } = await signIn(server);
  const { assistantMessage } = await send(server, headers, "Hello");
  const done = (await events(server, headers, assistantMessage.id)).at(-1)!.data.message;
  assert.equal(done.status, "complete");
  assert.match(done.parts[0].text, /development model/);
});

test("a second message waits for the current reply", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { conv } = await send(server, headers, "One");
  const res = await server.app.inject({ method: "POST", url: `/v1/conversations/${conv.id}/messages`, headers, payload: { text: "Two" } });
  assert.equal(res.statusCode, 400);
});

test("after old events are pruned, a reconnect still ends with the stored message", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const { assistantMessage } = await send(server, headers, "Hello");
  const original = (await events(server, headers, assistantMessage.id)).at(-1)!.data.message;
  server.clock.now += 2 * 24 * 60 * 60 * 1000;
  const { ChatEngine } = await import("../src/chat.ts");
  assert.ok(new ChatEngine(server.deps).pruneEvents(24 * 60 * 60 * 1000) > 0);
  const later = await signIn(server); // the old access token has expired
  const replay = await events(server, later.headers, assistantMessage.id);
  assert.equal(replay.length, 1);
  assert.equal(replay[0].event, "message.done");
  assert.deepEqual(replay[0].data.message, original);
});
