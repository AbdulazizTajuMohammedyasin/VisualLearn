import assert from "node:assert/strict";
import { test } from "node:test";
import { openDb } from "../src/db.ts";
import { ModelRegistry } from "../src/models.ts";
import { openCodeConfig } from "../src/runners.ts";

test("opencode config names the routed model and passes the key through the environment only", () => {
  const models = new ModelRegistry(openDb(":memory:"));
  models.saveProvider({ id: "anthropic", kind: "api", npm: "@ai-sdk/anthropic", baseUrl: null, apiKey: "sk-secret", models: ["claude-opus-5-5"] });
  const { json, env } = openCodeConfig(models, "anthropic/claude-opus-5-5");
  assert.equal(json.model, "anthropic/claude-opus-5-5");
  assert.deepEqual(json.provider.anthropic, {
    npm: "@ai-sdk/anthropic",
    name: "anthropic",
    options: { apiKey: "{env:VL_KEY_ANTHROPIC}" },
    models: { "claude-opus-5-5": {} },
  });
  assert.deepEqual(env, { VL_KEY_ANTHROPIC: "sk-secret" });
  assert.ok(!JSON.stringify(json).includes("sk-secret"));
});

test("a local provider gets its base URL and no key", () => {
  const models = new ModelRegistry(openDb(":memory:"));
  models.saveProvider({ id: "lab-gpu", kind: "local", npm: "@ai-sdk/openai-compatible", baseUrl: "http://10.0.0.5:8000/v1", apiKey: null, models: ["qwen"] });
  const { json, env } = openCodeConfig(models, "lab-gpu/qwen");
  assert.deepEqual(json.provider["lab-gpu"].options, { baseURL: "http://10.0.0.5:8000/v1" });
  assert.deepEqual(env, {});
});
