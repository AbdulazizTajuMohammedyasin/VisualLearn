import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { after, test } from "node:test";
import { learnAllowed, learnEnv, learnerToken, learnFileName, type LearnInstance, type LearnLaunch, type LearnLauncher } from "../src/learn.ts";
import { signIn, testServer, type TestServer } from "./helpers.ts";

interface Seen {
  userId: string;
  method: string;
  url: string;
  headers: http.IncomingHttpHeaders;
  body: string;
}

/** Stands in for LearnHarness: one small HTTP server per learner that records what reaches it. */
function fakeHarness() {
  const seen: Seen[] = [];
  const launches: LearnLaunch[] = [];
  const stopped: string[] = [];
  let hold: (() => void) | null = null;
  const launcher: LearnLauncher = async (launch) => {
    launches.push(launch);
    const server = http.createServer((req, res) => {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        seen.push({ userId: launch.userId, method: req.method!, url: req.url!, headers: req.headers, body });
        if (req.url!.endsWith("/stream")) {
          res.writeHead(200, { "content-type": "text/event-stream" });
          res.write('data: {"type":"turn-started"}\n\n');
          const finish = () => res.end('data: {"type":"turn-settled"}\n\ndata: [DONE]\n\n');
          if (req.url!.includes("held")) hold = finish;
          else finish();
          return;
        }
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ user: launch.userId, path: req.url, body: body || null }));
      });
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    let closed!: () => void;
    const exited = new Promise<void>((r) => (closed = r));
    const instance: LearnInstance = {
      port: (server.address() as AddressInfo).port,
      secret: `secret-for-${launch.userId}`,
      exited,
      stop: async () => {
        stopped.push(launch.userId);
        server.closeAllConnections();
        await new Promise<void>((r) => server.close(() => r()));
        closed();
      },
    };
    return instance;
  };
  return { launcher, seen, launches, stopped, release: () => hold?.() };
}

const servers: TestServer[] = [];
// Closing the app stops every learner's process, so nothing is left listening.
after(() => Promise.all(servers.map((s) => s.app.close())));

async function setup(options: { maxInstances?: number; idleMs?: number; env?: Record<string, string> } = {}) {
  const fake = fakeHarness();
  const server = await testServer({ VL_ADMIN_EMAILS: "admin@example.com", ...options.env }, null, {
    learn: { launcher: fake.launcher, maxInstances: options.maxInstances ?? 5, idleMs: options.idleMs ?? 60_000 },
  });
  servers.push(server);
  return { server, fake };
}

const learn = (server: TestServer, headers: Record<string, string>, method: string, path: string, payload?: object) =>
  server.app.inject({ method: method as "GET", url: `/v1/learn${path}`, headers, payload });

test("lessons need a signed-in learner", async () => {
  const { server } = await setup();
  assert.equal((await server.app.inject({ method: "GET", url: "/v1/learn/api/chats" })).statusCode, 401);
});

test("a learner's requests reach their own LearnHarness with its secret, not their token", async () => {
  const { server, fake } = await setup();
  const { headers, user } = await signIn(server);
  const created = await learn(server, headers, "POST", "/api/chats?x=1", { title: "Engines" });
  assert.equal(created.statusCode, 200);
  assert.deepEqual(created.json(), { user: user.id, path: "/api/chats?x=1", body: '{"title":"Engines"}' });
  const request = fake.seen.at(-1)!;
  assert.equal(request.headers["x-learnharness-desktop"], `secret-for-${user.id}`);
  assert.equal(request.headers.authorization, undefined);
  assert.equal(request.headers["content-type"], "application/json");
});

test("each learner gets a separate process and folders confined to their own files", async () => {
  const { server, fake } = await setup();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  assert.equal((await learn(server, a.headers, "GET", "/api/chats")).json().user, a.user.id);
  assert.equal((await learn(server, b.headers, "GET", "/api/chats")).json().user, b.user.id);
  await learn(server, a.headers, "GET", "/api/graph");
  assert.equal(fake.launches.length, 2, "the second request of a reuses its process");
  const [la, lb] = fake.launches;
  assert.notEqual(la.dirs.root, lb.dirs.root);
  assert.equal(la.env.LH_DATA_DIR, la.dirs.data);
  assert.equal(la.env.LH_FILES_ROOT, la.dirs.files);
  assert.equal(la.env.HOME, la.dirs.home);
  assert.equal(readFileSync(la.dirs.auth, "utf8").trim(), "{}");
});

test("keys, providers, subscriptions, prompts and model changes stay with the admin", async () => {
  const { server, fake } = await setup();
  const { headers } = await signIn(server);
  for (const [method, path] of [
    ["GET", "/api/keys"],
    ["PUT", "/api/keys/GEMINI_API_KEY"],
    ["POST", "/api/custom-provider"],
    ["GET", "/api/subscriptions"],
    ["GET", "/api/claude-code"],
    ["PUT", "/api/settings/agent"],
    ["PUT", "/api/chats/chat_1/settings"],
    ["GET", "/api/stt/token"],
    ["GET", "/api/../api/keys"],
  ]) {
    assert.equal((await learn(server, headers, method, path, method === "GET" ? undefined : {})).statusCode, 403, `${method} ${path}`);
  }
  assert.equal(fake.launches.length, 0, "refused requests don't start a process");
  assert.ok(learnAllowed("GET", "/api/chats/chat_1/stream"));
  assert.ok(learnAllowed("POST", "/api/graph/import"));
  assert.ok(!learnAllowed("POST", "/api/keys"));
});

test("a lesson's event stream passes through as an event stream", async () => {
  const { server } = await setup();
  const { headers } = await signIn(server);
  const res = await learn(server, headers, "GET", "/api/chats/chat_1/stream");
  assert.equal(res.headers["content-type"], "text/event-stream");
  assert.match(res.body, /turn-started[\s\S]*turn-settled[\s\S]*\[DONE\]/);
});

test("the admin's lesson models and keys reach the process; changing them restarts it", async () => {
  const { server, fake } = await setup();
  const admin = await signIn(server, "admin@example.com");
  const put = (url: string, payload: object) => server.app.inject({ method: "PUT", url, headers: admin.headers, payload });
  await put("/v1/admin/providers/google", { kind: "api", npm: "@ai-sdk/google", apiKey: "gm-key", models: ["gemini-a", "gemini-b"] });
  const routing = (learn: object) => put("/v1/admin/routing", { chat: { model: "dev/canned" }, figures: { model: "dev/canned" }, learn });
  assert.equal((await routing({ strong: "google/gemini-x", cheap: "google/gemini-a", vision: "google/gemini-a" })).statusCode, 400);
  assert.equal((await routing({ strong: "google/gemini-a", cheap: "google/gemini-b", vision: "google/gemini-b" })).statusCode, 200);

  const learner = await signIn(server);
  await learn(server, learner.headers, "GET", "/api/chats");
  const launch = fake.launches.at(-1)!;
  assert.equal(launch.env.GEMINI_API_KEY, "gm-key");
  const settings = JSON.parse(readFileSync(`${launch.dirs.data}/settings.json`, "utf8"));
  assert.deepEqual(settings.models.strong, { model: { provider: "google", id: "gemini-a" } });
  assert.deepEqual(settings.models.vision, { model: { provider: "google", id: "gemini-b" } });

  await routing({ strong: "google/gemini-b", cheap: "google/gemini-b", vision: "google/gemini-b" });
  await new Promise((r) => setTimeout(r, 20));
  assert.deepEqual(fake.stopped, [learner.user.id], "the idle process was stopped");
  await learn(server, learner.headers, "GET", "/api/chats");
  assert.equal(fake.launches.length, 2);
  assert.equal(JSON.parse(readFileSync(`${launch.dirs.data}/settings.json`, "utf8")).models.strong.model.id, "gemini-b");
  assert.equal((await server.app.inject({ method: "DELETE", url: "/v1/admin/providers/google", headers: admin.headers })).statusCode, 409, "lessons still use it");
});

test("at capacity the longest-idle learner makes way, and a busy server says so", async () => {
  const { server, fake } = await setup({ maxInstances: 1 });
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  await learn(server, a.headers, "GET", "/api/chats");
  await learn(server, b.headers, "GET", "/api/chats");
  assert.deepEqual(fake.stopped, [a.user.id]);

  // b keeps a lesson stream open; a must wait.
  const open = learn(server, b.headers, "GET", "/api/chats/held/stream");
  while (!fake.seen.some((s) => s.url.includes("held"))) await new Promise((r) => setTimeout(r, 5));
  const busy = await learn(server, a.headers, "GET", "/api/chats");
  assert.equal(busy.statusCode, 503);
  assert.equal(busy.json().error.code, "lessons_busy");
  fake.release();
  await open;
  assert.equal((await learn(server, a.headers, "GET", "/api/chats")).statusCode, 200);
});

test("idle processes are stopped after the idle time", async () => {
  const { server, fake } = await setup({ idleMs: 30 });
  const { headers, user } = await signIn(server);
  await learn(server, headers, "GET", "/api/chats");
  server.clock.now += 60_000;
  const end = Date.now() + 2000;
  while (!fake.stopped.length && Date.now() < end) await new Promise((r) => setTimeout(r, 10));
  assert.deepEqual(fake.stopped, [user.id]);
});

test("deleting an account stops its process and deletes its lessons", async () => {
  const { server, fake } = await setup();
  const { headers } = await signIn(server);
  await learn(server, headers, "GET", "/api/chats");
  const root = fake.launches[0].dirs.root;
  assert.ok(existsSync(root));
  await server.app.inject({ method: "DELETE", url: "/v1/me", headers });
  const end = Date.now() + 2000;
  while (existsSync(root) && Date.now() < end) await new Promise((r) => setTimeout(r, 10));
  assert.ok(!existsSync(root));
  assert.equal(fake.stopped.length, 1);
});

test("new lessons and imports count against the work limit", async () => {
  const { server } = await setup({ env: { VL_RATE_WORK_PER_MIN: "1" } });
  const { headers } = await signIn(server);
  assert.equal((await learn(server, headers, "POST", "/api/chat", { chatId: "c", input: { type: "text", text: "hi" } })).statusCode, 200);
  assert.equal((await learn(server, headers, "POST", "/api/chat", { chatId: "c", input: { type: "text", text: "hi" } })).statusCode, 429);
  assert.equal((await learn(server, headers, "GET", "/api/chats")).statusCode, 200, "reading isn't work");
});

test("a learner's process gets host basics, its own paths and keys, and none of this server's secrets", () => {
  process.env.VL_SIGNING_SECRET_FOR_TEST = "server-only";
  process.env.ANTHROPIC_API_KEY_FOR_TEST = "ambient";
  try {
    const env = learnEnv(
      { userId: "u", dirs: { root: "/r", data: "/r/data", auth: "/r/auth.json", files: "/r/files", home: "/r/home", log: "/r/harness.log" }, env: { GEMINI_API_KEY: "k", LH_DATA_DIR: "/r/data" } },
      { port: 4100, secret: "s".repeat(64), chromePath: "/usr/bin/chromium", dev: false },
    );
    assert.equal(env.GEMINI_API_KEY, "k");
    assert.equal(env.LH_DATA_DIR, "/r/data");
    assert.equal(env.LH_DESKTOP_SERVER, "1");
    assert.equal(env.LH_DESKTOP_API_SECRET, "s".repeat(64));
    assert.equal(env.HOSTNAME, "127.0.0.1");
    assert.equal(env.PORT, "4100");
    assert.equal(env.CHROME_PATH, "/usr/bin/chromium");
    assert.equal(env.VL_SIGNING_SECRET_FOR_TEST, undefined);
    assert.equal(env.ANTHROPIC_API_KEY_FOR_TEST, undefined);
    assert.ok(env.PATH ?? env.Path);
  } finally {
    delete process.env.VL_SIGNING_SECRET_FOR_TEST;
    delete process.env.ANTHROPIC_API_KEY_FOR_TEST;
  }
});

test("a university endpoint becomes LearnHarness's custom provider, bound to its URL, with the key only in the environment", async () => {
  const { server, fake } = await setup();
  const admin = await signIn(server, "admin@example.com");
  const put = (url: string, payload: object) => server.app.inject({ method: "PUT", url, headers: admin.headers, payload });
  const lab = {
    kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: "https://gpu.uni.example/v1/", apiKey: "lab-key", models: ["qwen-big", "qwen-vl"],
    modelInfo: { "qwen-big": { context: 131072, maxTokens: 16384, reasoning: true }, "qwen-vl": { context: 65536, image: true } },
  };
  assert.equal((await put("/v1/admin/providers/lab", { ...lab, modelInfo: { other: {} } })).statusCode, 400, "limits only for listed models");
  assert.equal((await put("/v1/admin/providers/lab", { ...lab, modelInfo: { "qwen-big": { context: -5 } } })).statusCode, 400);
  assert.equal((await put("/v1/admin/providers/lab", lab)).statusCode, 200);
  await put("/v1/admin/providers/lab2", { kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: "http://other:8000/v1", models: ["m"] });
  await put("/v1/admin/providers/google", { kind: "api", npm: "@ai-sdk/google", apiKey: "gm-key", models: ["gemini-a"] });
  const routing = (learn: object) => put("/v1/admin/routing", { chat: { model: "dev/canned" }, figures: { model: "dev/canned" }, learn });
  assert.equal((await routing({ strong: "lab/qwen-big", cheap: "lab2/m", vision: "lab/qwen-vl" })).statusCode, 400, "one custom endpoint only");
  assert.equal((await routing({ strong: "lab/qwen-big", cheap: "google/gemini-a", vision: "lab/qwen-vl" })).statusCode, 200);

  const learner = await signIn(server);
  await learn(server, learner.headers, "GET", "/api/chats");
  const launch = fake.launches.at(-1)!;
  const settings = JSON.parse(readFileSync(`${launch.dirs.data}/settings.json`, "utf8"));
  assert.deepEqual(settings.models.strong, { model: { provider: "custom", id: "qwen-big" } });
  assert.deepEqual(settings.models.cheap, { model: { provider: "google", id: "gemini-a" } });
  assert.deepEqual(settings.models.vision, { model: { provider: "custom", id: "qwen-vl" } });
  const cp = settings.customProvider;
  assert.equal(cp.baseUrl, "https://gpu.uni.example/v1");
  assert.equal(cp.authMode, "apiKey");
  assert.match(cp.credentialBindingId, /^[a-f0-9]{32}$/);
  assert.deepEqual(cp.models, [
    { id: "qwen-big", context: 131072, maxTokens: 16384, reasoning: true },
    { id: "qwen-vl", context: 65536, image: true },
  ]);
  const auth = JSON.parse(readFileSync(launch.dirs.auth, "utf8"));
  assert.deepEqual(JSON.parse(auth.__learnharness_custom_provider_binding), { version: 1, id: cp.credentialBindingId, baseUrl: cp.baseUrl, authMode: "apiKey" });
  assert.ok(!JSON.stringify(auth).includes("lab-key"), "the key is never written to disk");
  assert.equal(launch.env.CUSTOM_OPENAI_API_KEY, "lab-key");
  assert.equal(launch.env.GEMINI_API_KEY, "gm-key");

  // Back to API models only: the custom section and binding go away.
  await routing({ strong: "google/gemini-a", cheap: "google/gemini-a", vision: "google/gemini-a" });
  await new Promise((r) => setTimeout(r, 20));
  await learn(server, learner.headers, "GET", "/api/chats");
  assert.equal(JSON.parse(readFileSync(`${launch.dirs.data}/settings.json`, "utf8")).customProvider, undefined);
  assert.deepEqual(JSON.parse(readFileSync(launch.dirs.auth, "utf8")), {});
});

test("a learner's lessons can ask for 3D figures, only for that learner and only from this machine", async () => {
  const { server, fake } = await setup();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  await learn(server, a.headers, "GET", "/api/chats");
  const env = fake.launches[0].env;
  assert.equal(env.VL_FIGURE_URL, `http://127.0.0.1:${server.deps.config.port}/v1/internal`);
  assert.equal(env.VL_FIGURE_TOKEN, learnerToken(server.deps.config.signingSecret, a.user.id));

  const internal = (method: string, url: string, token: string | undefined, payload?: object, remoteAddress?: string) =>
    server.app.inject({ method: method as "GET", url: `/v1/internal${url}`, headers: token ? { "x-visuallearn-learner": token } : {}, payload, remoteAddress });
  assert.equal((await internal("POST", "/figures", undefined, { prompt: "x" })).statusCode, 403);
  assert.equal((await internal("POST", "/figures", `${a.user.id}.forged`, { prompt: "x" })).statusCode, 403);
  assert.equal((await internal("POST", "/figures", env.VL_FIGURE_TOKEN, { prompt: "x" }, "10.0.0.7")).statusCode, 403, "not from another machine");

  const created = await internal("POST", "/figures", env.VL_FIGURE_TOKEN, { title: "Engine", prompt: "One cylinder of a four-stroke engine." });
  assert.equal(created.statusCode, 201);
  const id = created.json().id;
  assert.equal((await internal("GET", `/figures/${id}`, env.VL_FIGURE_TOKEN)).json().prompt, "One cylinder of a four-stroke engine.");
  const mine = (await server.app.inject({ method: "GET", url: "/v1/figures", headers: a.headers })).json().items.map((f: { id: string }) => f.id);
  assert.ok(mine.includes(id), "it shows in the learner's figures");
  const bToken = learnerToken(server.deps.config.signingSecret, b.user.id);
  assert.equal((await internal("GET", `/figures/${id}`, bToken)).statusCode, 404, "another learner can't see it");
  const cancelled = await internal("POST", `/figures/${id}/cancel`, env.VL_FIGURE_TOKEN);
  assert.ok(["cancelled", "succeeded"].includes(cancelled.json().status));

});

test("a PDF from the phone lands in that learner's files folder, under a safe name", async () => {
  const { server } = await setup();
  const a = await signIn(server, "a@example.com");
  const b = await signIn(server, "b@example.com");
  const pdf = readFileSync(join(import.meta.dirname, "fixtures", "four-stroke-engine.pdf"));
  const upload = (headers: Record<string, string>, name: string, body: Buffer) =>
    server.app.inject({ method: "POST", url: `/v1/learn/files?name=${encodeURIComponent(name)}`, headers: { ...headers, "content-type": "application/pdf" }, payload: body });

  const res = await upload(a.headers, "../../etc/Engine notes.pdf", pdf);
  assert.equal(res.statusCode, 201);
  assert.deepEqual(res.json(), { name: "Engine notes.pdf", path: "~/Engine notes.pdf", bytes: pdf.length });
  const files = server.deps.learn!.dirs(a.user.id).files;
  assert.deepEqual(readFileSync(join(files, "Engine notes.pdf")), pdf);
  assert.ok(!existsSync(join(server.deps.learn!.dirs(b.user.id).files, "Engine notes.pdf")));
  assert.equal((await upload(a.headers, "x.pdf", Buffer.from("not a pdf"))).statusCode, 400);
  assert.equal((await server.app.inject({ method: "POST", url: "/v1/learn/files?name=x.pdf", payload: pdf, headers: { "content-type": "application/pdf" } })).statusCode, 401);
  assert.equal(learnFileName(".hidden"), "hidden.pdf");
  assert.equal(learnFileName(""), "document.pdf");
  assert.equal(learnFileName("C:\\Users\\x\\a:b*c?.PDF"), "a-b-c.pdf");
});
