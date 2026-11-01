/**
 * Runs the real OpenCode binary through OpenCodeRunner against a scripted, OpenAI-compatible
 * mock model, so the whole figure pipeline (generated config, XDG_CONFIG_HOME, our custom tools,
 * session capture, result collection) is checked without a model key.
 *
 *   VL_OPENCODE_BIN=/path/to/opencode npm run test:opencode
 */
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { openDb } from "../src/db.ts";
import { ModelRegistry } from "../src/models.ts";
import { OpenCodeRunner, type Stage } from "../src/runners.ts";

const bin = process.env.VL_OPENCODE_BIN;

/** Answers like an OpenAI chat model that reports progress, then calls finish. */
function mockModel() {
  const requests: { tools: string[]; toolResults: number; lastToolOutput: string | null }[] = [];
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const json = body ? JSON.parse(body) : {};
      const tools: string[] = (json.tools ?? []).map((t: { function: { name: string } }) => t.function.name);
      const toolResults = (json.messages ?? []).filter((m: { role: string }) => m.role === "tool").length;
      const toolMessages = (json.messages ?? []).filter((m: { role: string }) => m.role === "tool");
      const last = toolMessages.at(-1)?.content;
      requests.push({ tools, toolResults, lastToolOutput: last == null ? null : typeof last === "string" ? last : JSON.stringify(last) });
      let reply: { text?: string; call?: { name: string; args: unknown } };
      if (!tools.includes("finish")) reply = { text: "Gear figure" }; // e.g. title generation
      else if (toolResults === 0) reply = { call: { name: "report_progress", args: { stage: "research", message: "Reading about gears." } } };
      else if (toolResults === 1) reply = { call: { name: "finish", args: { title: "Gear train", summary: "Two gears mesh.", dark_page: "dark.html", light_page: "light.html" } } };
      else reply = { text: "Done." };

      const chunk = (delta: unknown, finish: string | null) =>
        `data: ${JSON.stringify({ id: "c1", object: "chat.completion.chunk", created: 0, model: "scripted", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      if (reply.call) {
        res.write(chunk({ role: "assistant", tool_calls: [{ index: 0, id: `call_${requests.length}`, type: "function", function: { name: reply.call.name, arguments: JSON.stringify(reply.call.args) } }] }, null));
        res.write(chunk({}, "tool_calls"));
      } else {
        res.write(chunk({ role: "assistant", content: reply.text }, null));
        res.write(chunk({}, "stop"));
      }
      res.end("data: [DONE]\n\n");
    });
  });
  return { server, requests };
}

test("a figure job runs end to end through opencode", { skip: !bin && "set VL_OPENCODE_BIN to run" }, async (t) => {
  const { server, requests } = mockModel();
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  t.after(() => server.close());
  const port = (server.address() as { port: number }).port;

  const models = new ModelRegistry(openDb(":memory:"));
  models.saveProvider({ id: "mock", kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: `http://127.0.0.1:${port}/v1`, apiKey: "test-key", models: ["scripted"] });
  models.saveRouting({ chat: { model: "dev/canned", fallback: null }, figures: { model: "mock/scripted", fallback: null } });

  const anatomy = mkdtempSync(join(tmpdir(), "vl-anatomy-"));
  writeFileSync(join(anatomy, "SKILL.md"), "---\nname: anatomy\ndescription: Builds interactive isometric figures.\n---\nBuild the figure.\n");
  const root = mkdtempSync(join(tmpdir(), "vl-opencode-"));
  const jobsDir = join(root, "jobs");
  const work = join(jobsDir, "fig_test", "work");
  mkdirSync(work, { recursive: true });
  writeFileSync(join(work, "dark.html"), "<p>dark gears</p>");
  writeFileSync(join(work, "light.html"), "<p>light gears</p>");

  const runner = new OpenCodeRunner({ bin: bin!, anatomyDir: anatomy, jobsDir, homeDir: join(root, "opencode"), timeoutMs: 180_000 }, models);
  const progress: [Stage, string][] = [];
  let session: string | null = null;
  const result = await runner.run(
    { id: "fig_test", prompt: "Show how two gears mesh", theme: "dark", sessionId: null },
    { progress: (s, m) => progress.push([s, m]), session: (id) => (session = id), signal: new AbortController().signal },
  ).catch((e) => {
    // What each tool told the model is the quickest clue when OpenCode stops early.
    throw new Error(`${e.message}
tool outputs: ${JSON.stringify(requests.map((r) => r.lastToolOutput))}`);
  });

  assert.equal(result.title, "Gear train");
  assert.equal(result.dark.toString(), "<p>dark gears</p>");
  assert.equal(result.light.toString(), "<p>light gears</p>");
  assert.deepEqual(progress, [["research", "Reading about gears."]]);
  assert.ok(session, "the opencode session id should be captured for retries");
  assert.ok(requests.some((r) => r.tools.includes("report_progress")), "our custom tools should be offered to the model");
});
