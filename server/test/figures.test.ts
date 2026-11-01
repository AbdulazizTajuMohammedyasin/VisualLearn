import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { test } from "node:test";
import type { Figures } from "../src/figures.ts";
import type { Previewer } from "../src/previews.ts";
import type { FigureJob, FigureResult, FigureRunner, RunHooks } from "../src/runners.ts";
import { signIn, testServer, type TestServer } from "./helpers.ts";

/** A runner whose jobs finish only when the test says so. */
class HeldRunner implements FigureRunner {
  started: string[] = [];
  private release = new Map<string, () => void>();

  run(job: FigureJob, hooks: RunHooks): Promise<FigureResult> {
    this.started.push(job.prompt);
    hooks.progress("research", "Looking things up.");
    return new Promise((resolve, reject) => {
      hooks.signal.addEventListener("abort", () => reject(hooks.signal.reason), { once: true });
      this.release.set(job.prompt, () =>
        resolve({ title: job.prompt, summary: "Done.", dark: Buffer.from(`<p>${job.prompt} dark</p>`), light: Buffer.from(`<p>${job.prompt} light</p>`) }),
      );
    });
  }

  finish(prompt: string) {
    this.release.get(prompt)!();
  }
}

const create = (server: TestServer, headers: Record<string, string>, prompt: string) =>
  server.app.inject({ method: "POST", url: "/v1/figures", headers, payload: { prompt, theme: "dark" } });

const get = async (server: TestServer, headers: Record<string, string>, id: string) =>
  (await server.app.inject({ method: "GET", url: `/v1/figures/${id}`, headers })).json();

const tick = () => new Promise((r) => setTimeout(r, 20));

/** Waits until [condition] holds (jobs start and finish asynchronously). */
async function until(condition: () => boolean | Promise<boolean>, ms = 3000) {
  const end = Date.now() + ms;
  while (!(await condition())) {
    if (Date.now() > end) throw new Error("timed out waiting");
    await tick();
  }
}

test("a figure runs through its stages and its pages download with matching sha256", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const res = await create(server, headers, "How a pin tumbler lock opens");
  assert.equal(res.statusCode, 202);
  const id = res.json().id;

  const events = await server.app.inject({ method: "GET", url: `/v1/figures/${id}/events`, headers });
  const updates = events.body.split("\n\n").filter((b) => b.includes("figure.update")).map((b) => JSON.parse(b.split("data: ")[1]).figure);
  assert.ok(updates.some((f) => f.stage === "motion"));
  const done = updates.at(-1);
  assert.equal(done.status, "succeeded");
  assert.equal(done.progress, 1);
  assert.equal(done.title, "Dial indicator on gauge blocks");

  for (const theme of ["dark", "light"] as const) {
    const page = done.pages[theme];
    const download = await server.app.inject({ method: "GET", url: page.url.replace(server.deps.config.publicUrl, "") });
    assert.equal(download.statusCode, 200);
    assert.equal(createHash("sha256").update(download.rawPayload).digest("hex"), page.sha256);
    assert.equal(download.rawPayload.length, page.bytes);
  }
});

test("a tampered or expired page link is refused", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const id = (await create(server, headers, "x")).json().id;
  await server.app.inject({ method: "GET", url: `/v1/figures/${id}/events`, headers });
  const url = (await get(server, headers, id)).pages.dark.url.replace(server.deps.config.publicUrl, "");
  assert.equal((await server.app.inject({ method: "GET", url: url.replace(/sig=./, "sig=A") })).statusCode, 404);
  server.clock.now += 2 * 60 * 60 * 1000;
  assert.equal((await server.app.inject({ method: "GET", url })).statusCode, 404);
});

test("the queue is shared fairly: a newcomer's first job goes ahead of someone's third", async () => {
  const runner = new HeldRunner();
  const server = await testServer({}, null, { workers: 1, runner });
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  await create(server, a.headers, "a1");
  const a2 = (await create(server, a.headers, "a2")).json();
  const a3 = (await create(server, a.headers, "a3")).json();
  const b1 = (await create(server, b.headers, "b1")).json();
  await tick();
  assert.deepEqual(runner.started, ["a1"]);
  assert.equal((await get(server, b.headers, b1.id)).queuePosition, 1);
  assert.equal((await get(server, a.headers, a2.id)).queuePosition, 2);
  assert.equal((await get(server, a.headers, a3.id)).queuePosition, 3);
  assert.ok((await get(server, b.headers, b1.id)).estimatedStartAt);

  runner.finish("a1");
  await until(() => runner.started.length === 2);
  assert.deepEqual(runner.started, ["a1", "b1"]);
  assert.equal((await get(server, a.headers, a2.id)).queuePosition, 1);
});

test("idle workers let one person's jobs run side by side", async () => {
  const runner = new HeldRunner();
  const server = await testServer({}, null, { workers: 2, runner });
  const { headers } = await signIn(server);
  await create(server, headers, "one");
  await create(server, headers, "two");
  await tick();
  assert.deepEqual(runner.started, ["one", "two"]);
});

test("more than ten waiting figures is refused", async () => {
  const runner = new HeldRunner();
  const server = await testServer({}, null, { workers: 1, runner });
  const { headers } = await signIn(server);
  for (let i = 0; i < 11; i++) assert.equal((await create(server, headers, `f${i}`)).statusCode, 202);
  const res = await create(server, headers, "one too many");
  assert.equal(res.statusCode, 429);
});

test("cancel stops a running job and retry queues it again", async () => {
  const runner = new HeldRunner();
  const server = await testServer({}, null, { workers: 1, runner });
  const { headers } = await signIn(server);
  const id = (await create(server, headers, "job")).json().id;
  await tick();
  const cancelled = (await server.app.inject({ method: "POST", url: `/v1/figures/${id}/cancel`, headers })).json();
  assert.equal(cancelled.status, "cancelled");
  assert.equal((await server.app.inject({ method: "POST", url: `/v1/figures/${id}/cancel`, headers })).statusCode, 409);
  await tick();
  const retried = (await server.app.inject({ method: "POST", url: `/v1/figures/${id}/retry`, headers })).json();
  assert.equal(retried.status, "running");
  runner.finish("job");
  await until(async () => (await get(server, headers, id)).status === "succeeded");
});

test("deleting a figure removes it, its pages and records it for sync", async () => {
  const server = await testServer();
  const { headers, user } = await signIn(server);
  const id = (await create(server, headers, "x")).json().id;
  await server.app.inject({ method: "GET", url: `/v1/figures/${id}/events`, headers });
  const url = (await get(server, headers, id)).pages.dark.url.replace(server.deps.config.publicUrl, "");
  assert.equal((await server.app.inject({ method: "DELETE", url: `/v1/figures/${id}`, headers })).statusCode, 204);
  assert.equal((await server.app.inject({ method: "GET", url: `/v1/figures/${id}`, headers })).statusCode, 404);
  assert.equal((await server.app.inject({ method: "GET", url })).statusCode, 404);
  const row = server.deps.db.prepare("SELECT kind FROM deletions WHERE user_id = ? AND item_id = ?").get(user.id, id) as { kind: string };
  assert.equal(row.kind, "figure");
});

/** Writes a fake PNG and remembers which pages it was asked to draw. */
class FakePreviewer implements Previewer {
  drawn: string[] = [];
  fail = false;

  async render(pageFile: string, outPng: string) {
    if (this.fail) throw new Error("no browser");
    this.drawn.push(readFileSync(pageFile, "utf8"));
    writeFileSync(outPng, PNG);
  }
}
const PNG = Buffer.from("89504e470d0a1a0a", "hex");

test("a finished figure gets a preview image of its dark page, served signed and deleted with it", async () => {
  const previewer = new FakePreviewer();
  const runner = new HeldRunner();
  const server = await testServer({}, null, { runner, previewer });
  const { headers } = await signIn(server);
  const id = (await create(server, headers, "lock")).json().id;
  await until(() => runner.started.length === 1);
  assert.equal((await get(server, headers, id)).previewUrl, null);

  runner.finish("lock");
  await until(async () => (await get(server, headers, id)).previewUrl !== null);
  assert.deepEqual(previewer.drawn, ["<p>lock dark</p>"]);
  const url = (await get(server, headers, id)).previewUrl.replace(server.deps.config.publicUrl, "");
  const image = await server.app.inject({ method: "GET", url });
  assert.equal(image.statusCode, 200);
  assert.equal(image.headers["content-type"], "image/png");
  assert.deepEqual(image.rawPayload, PNG);
  // The signature covers the file name: a page link can't be turned into a preview link.
  assert.equal((await server.app.inject({ method: "GET", url: url.replace(".png", ".html") })).statusCode, 404);

  await server.app.inject({ method: "DELETE", url: `/v1/figures/${id}`, headers });
  assert.equal((await server.app.inject({ method: "GET", url })).statusCode, 404);
});

test("a figure whose preview can't be drawn still finishes, without one", async () => {
  const previewer = new FakePreviewer();
  previewer.fail = true;
  const server = await testServer({}, null, { previewer });
  const { headers } = await signIn(server);
  const id = (await create(server, headers, "x")).json().id;
  await server.app.inject({ method: "GET", url: `/v1/figures/${id}/events`, headers });
  await (server.deps.figures as Figures).previewsSettled();
  const figure = await get(server, headers, id);
  assert.equal(figure.status, "succeeded");
  assert.equal(figure.previewUrl, null);
});

test("other users can't see a figure", async () => {
  const server = await testServer();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  const id = (await create(server, a.headers, "mine")).json().id;
  assert.equal((await server.app.inject({ method: "GET", url: `/v1/figures/${id}`, headers: b.headers })).statusCode, 404);
  assert.deepEqual((await server.app.inject({ method: "GET", url: "/v1/figures", headers: b.headers })).json().items, []);
});

test("the Figure button in chat starts a figure job in the reply", async () => {
  const server = await testServer();
  const { headers } = await signIn(server);
  const conv = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers, payload: {} })).json();
  const sent = (await server.app.inject({ method: "POST", url: `/v1/conversations/${conv.id}/messages`, headers, payload: { text: "a gearbox", mode: "figure" } })).json();
  const stream = await server.app.inject({ method: "GET", url: `/v1/messages/${sent.assistantMessage.id}/events`, headers });
  const done = JSON.parse(stream.body.split("event: message.done\ndata: ")[1].split("\n")[0]).message;
  const figurePart = done.parts.find((p: { type: string }) => p.type === "figure");
  assert.ok(figurePart);
  const figure = await get(server, headers, figurePart.figureId);
  assert.equal(figure.conversationId, conv.id);
});
