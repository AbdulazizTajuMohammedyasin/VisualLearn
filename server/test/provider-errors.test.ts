import assert from "node:assert/strict";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { test } from "node:test";
import { providerProblem } from "../src/errors.ts";
import { FigureFailure, type FigureJob, type FigureResult, type FigureRunner, type RunHooks } from "../src/runners.ts";
import { signIn, testServer } from "./helpers.ts";

test("provider errors are told apart: out of quota, busy, or something else", () => {
  assert.equal(providerProblem("AI_APICallError: You exceeded your current quota, please check your plan and billing details.")?.code, "provider_quota");
  assert.equal(providerProblem('{"error":{"status":"RESOURCE_EXHAUSTED"}}')?.code, "provider_quota");
  assert.equal(providerProblem("This model is currently experiencing high demand. Please try again later.")?.code, "provider_busy");
  assert.equal(providerProblem("Overloaded")?.code, "provider_busy");
  assert.equal(providerProblem("ENOENT: no such file, open result.json"), null);
});

class QuotaRunner implements FigureRunner {
  async run(_job: FigureJob, hooks: RunHooks): Promise<FigureResult> {
    hooks.progress("research", "Looking things up.");
    throw new FigureFailure("opencode exited 1", providerProblem("You exceeded your current quota"));
  }
}

test("a figure stopped by the provider's quota says so", async () => {
  const server = await testServer({}, null, { runner: new QuotaRunner() });
  const { headers } = await signIn(server);
  const id = (await server.app.inject({ method: "POST", url: "/v1/figures", headers, payload: { prompt: "An engine" } })).json().id;
  await server.app.inject({ method: "GET", url: `/v1/figures/${id}/events`, headers });
  const figure = (await server.app.inject({ method: "GET", url: `/v1/figures/${id}`, headers })).json();
  assert.equal(figure.status, "failed");
  assert.equal(figure.error.code, "provider_quota");
  assert.match(figure.error.message, /usage limit/);
});

test("a chat reply stopped by the provider's quota says so", async () => {
  // Answers like the Anthropic API does when an account is out of credit.
  const mock = createServer((_req, res) => {
    res.writeHead(429, { "content-type": "application/json" });
    res.end(JSON.stringify({ type: "error", error: { type: "rate_limit_error", message: "You exceeded your current quota, please check your plan and billing details." } }));
  });
  await new Promise<void>((r) => mock.listen(0, "127.0.0.1", r));
  try {
    const server = await testServer({ VL_ADMIN_EMAILS: "admin@example.com" });
    const admin = await signIn(server, "admin@example.com");
    const put = (url: string, payload: object) => server.app.inject({ method: "PUT", url, headers: admin.headers, payload });
    await put("/v1/admin/providers/claude", { kind: "api", npm: "@ai-sdk/anthropic", baseUrl: `http://127.0.0.1:${(mock.address() as AddressInfo).port}/v1`, apiKey: "k", models: ["m"] });
    await put("/v1/admin/routing", { chat: { model: "claude/m", fallback: null }, figures: { model: "dev/canned", fallback: null } });

    const chat = (await server.app.inject({ method: "POST", url: "/v1/conversations", headers: admin.headers, payload: {} })).json().id;
    const sent = (await server.app.inject({ method: "POST", url: `/v1/conversations/${chat}/messages`, headers: admin.headers, payload: { text: "Hi" } })).json();
    const events = await server.app.inject({ method: "GET", url: `/v1/messages/${sent.assistantMessage.id}/events`, headers: admin.headers });
    const done = events.body.split("\n\n").find((b) => b.includes("event: message.done"))!;
    const message = JSON.parse(done.split("data: ")[1]).message;
    assert.equal(message.status, "failed");
    assert.equal(message.error.code, "provider_quota");
  } finally {
    mock.close();
  }
});
