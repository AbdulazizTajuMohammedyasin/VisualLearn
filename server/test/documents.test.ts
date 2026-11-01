import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { join } from "node:path";
import { test } from "node:test";
import { signIn, testServer, type TestServer } from "./helpers.ts";

const PDF = readFileSync(join(import.meta.dirname, "fixtures", "four-stroke-engine.pdf"));

const newChat = async (server: TestServer, headers: Record<string, string>) =>
  (await server.app.inject({ method: "POST", url: "/v1/conversations", headers, payload: {} })).json().id as string;

const upload = (server: TestServer, headers: Record<string, string>, chat: string, body: Buffer, name = "four-stroke-engine.pdf") =>
  server.app.inject({
    method: "POST",
    url: `/v1/conversations/${chat}/documents?name=${encodeURIComponent(name)}`,
    headers: { ...headers, "content-type": "application/pdf" },
    payload: body,
  });

/** The finished reply's parts, read from its event stream. */
async function reply(server: TestServer, headers: Record<string, string>, messageId: string) {
  const res = await server.app.inject({ method: "GET", url: `/v1/messages/${messageId}/events`, headers });
  const done = res.body.split("\n\n").find((b) => b.includes("event: message.done"))!;
  return JSON.parse(done.split("data: ")[1]).message;
}

test("an uploaded PDF is read: its pages are counted and its text kept", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const res = await upload(server, headers, chat, PDF);
  assert.equal(res.statusCode, 201, res.body);
  const doc = res.json();
  assert.match(doc.id, /^doc_/);
  assert.equal(doc.name, "four-stroke-engine.pdf");
  assert.equal(doc.pages, 1);
  assert.equal(doc.bytes, PDF.length);
  assert.equal(doc.hasText, true);
  assert.match(server.deps.documents!.get(doc.id)!.text, /--- page 1 ---\nThe four-stroke engine/);
});

test("files that aren't PDFs, damaged PDFs and other people's chats are refused", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  assert.equal((await upload(server, headers, chat, Buffer.from("hello"))).statusCode, 400);
  assert.equal((await upload(server, headers, chat, Buffer.from("%PDF-1.7 not really"))).statusCode, 400);
  const other = await signIn(server, "other@example.com");
  assert.equal((await upload(server, other.headers, chat, PDF)).statusCode, 404);
  const wrongType = await server.app.inject({
    method: "POST", url: `/v1/conversations/${chat}/documents`, headers: { ...headers, "content-type": "image/png" }, payload: Buffer.from("x"),
  });
  assert.equal(wrongType.statusCode, 415);
});

test("a PDF over 25 MB is refused with a clear message", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const res = await upload(server, headers, chat, Buffer.concat([Buffer.from("%PDF-1.7\n"), Buffer.alloc(26 * 1024 * 1024)]));
  assert.equal(res.statusCode, 413);
  assert.equal(res.json().error.code, "too_large");
});

test("sending a PDF alone gets a study pack: steps, flashcards, a quiz and a figure", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const doc = (await upload(server, headers, chat, PDF)).json();
  const res = await server.app.inject({ method: "POST", url: `/v1/conversations/${chat}/messages`, headers, payload: { documentIds: [doc.id] } });
  assert.equal(res.statusCode, 200, res.body);
  const { userMessage, assistantMessage } = res.json();
  assert.deepEqual(userMessage.parts, [{ type: "document", documentId: doc.id, name: "four-stroke-engine.pdf", pages: 1 }]);

  const message = await reply(server, headers, assistantMessage.id);
  const programs = message.parts.filter((p: { type: string }) => p.type === "ui").map((p: { program: string }) => p.program);
  assert.ok(programs.some((p: string) => p.includes("StepExplainer(")));
  assert.ok(programs.some((p: string) => p.includes("Flashcards(")));
  assert.ok(programs.some((p: string) => p.includes("Quiz(")));
  assert.equal(message.parts.at(-1).type, "figure");
  // The model saw the document's text, not just its name.
  assert.match(message.parts[0].text, /The four-stroke engine/);

  const conversation = (await server.app.inject({ method: "GET", url: `/v1/conversations/${chat}`, headers })).json();
  assert.equal(conversation.title, "four-stroke-engine");
});

test("documents must belong to the chat, and a message takes at most three", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const elsewhere = await newChat(server, headers);
  const doc = (await upload(server, headers, elsewhere, PDF)).json();
  const send = (documentIds: unknown) =>
    server.app.inject({ method: "POST", url: `/v1/conversations/${chat}/messages`, headers, payload: { text: "Explain", documentIds } });
  assert.equal((await send([doc.id])).statusCode, 404);
  assert.equal((await send(["a", "b", "c", "d"])).statusCode, 400);
  assert.equal((await send("doc_1")).statusCode, 400);
});

test("deleting the chat deletes its PDFs from disk", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const doc = (await upload(server, headers, chat, PDF)).json();
  assert.ok(server.deps.documents!.read(doc.id));
  await server.app.inject({ method: "DELETE", url: `/v1/conversations/${chat}`, headers });
  assert.equal(server.deps.documents!.get(doc.id), undefined);
  assert.equal(server.deps.documents!.read(doc.id), null);
});

test("deleting the account deletes its PDFs from disk", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  const doc = (await upload(server, headers, chat, PDF)).json();
  await server.app.inject({ method: "DELETE", url: "/v1/me", headers });
  assert.equal(server.deps.documents!.read(doc.id), null);
});

test("uploads count against the work rate limit", async () => {
  const server = await testServer({ VL_RATE_WORK_PER_MIN: "2" });
  const { headers } = await signIn(server);
  const chat = await newChat(server, headers);
  assert.equal((await upload(server, headers, chat, PDF)).statusCode, 201);
  assert.equal((await upload(server, headers, chat, PDF)).statusCode, 201);
  assert.equal((await upload(server, headers, chat, PDF)).statusCode, 429);
});

/** Answers like the Anthropic Messages API and keeps the request bodies it was sent. */
async function mockAnthropic() {
  const bodies: any[] = [];
  const server = createServer(async (req, res) => {
    let raw = "";
    for await (const chunk of req) raw += chunk;
    bodies.push(JSON.parse(raw));
    res.writeHead(200, { "content-type": "text/event-stream" });
    const send = (event: string, data: unknown) => res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    send("message_start", { type: "message_start", message: { id: "msg_1", type: "message", role: "assistant", model: "m", content: [], stop_reason: null, usage: { input_tokens: 1, output_tokens: 0 } } });
    send("content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } });
    send("content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Read it." } });
    send("content_block_stop", { type: "content_block_stop", index: 0 });
    send("message_delta", { type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 2 } });
    send("message_stop", { type: "message_stop" });
    res.end();
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1`, bodies, close: () => server.close() };
}

test("models that read PDFs are sent the file itself", async () => {
  const mock = await mockAnthropic();
  try {
    const server = await testServer({ VL_ADMIN_EMAILS: "admin@example.com" });
    const admin = await signIn(server, "admin@example.com");
    const put = (url: string, payload: object) => server.app.inject({ method: "PUT", url, headers: admin.headers, payload });
    assert.equal((await put("/v1/admin/providers/claude", { kind: "api", npm: "@ai-sdk/anthropic", baseUrl: mock.url, apiKey: "test", models: ["m"] })).statusCode, 200);
    assert.equal((await put("/v1/admin/routing", { chat: { model: "claude/m", fallback: null }, figures: { model: "dev/canned", fallback: null } })).statusCode, 200);

    const chat = await newChat(server, admin.headers);
    const doc = (await upload(server, admin.headers, chat, PDF)).json();
    const sent = await server.app.inject({ method: "POST", url: `/v1/conversations/${chat}/messages`, headers: admin.headers, payload: { documentIds: [doc.id] } });
    const message = await reply(server, admin.headers, sent.json().assistantMessage.id);
    assert.equal(message.parts[0].text, "Read it.");

    const content = mock.bodies[0].messages.at(-1).content;
    const file = content.find((c: { type: string }) => c.type === "document");
    assert.equal(file.source.media_type, "application/pdf");
    assert.deepEqual(Buffer.from(file.source.data, "base64"), PDF);
    assert.equal(content.at(-1).text, "Help me learn what's in this document.");
    assert.match(mock.bodies[0].system.map((s: { text: string }) => s.text).join(""), /study pack/);
  } finally {
    mock.close();
  }
});
