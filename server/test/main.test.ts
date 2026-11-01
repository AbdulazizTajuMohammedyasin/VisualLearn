import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

test("the real entry point serves every route group", async (t) => {
  const port = 18_000 + Math.floor(Math.random() * 1000);
  const child = spawn(process.execPath, ["--disable-warning=ExperimentalWarning", "src/main.ts"], {
    env: { ...process.env, PORT: String(port), HOST: "127.0.0.1", VL_DEV_AUTH: "1", VL_DATA_DIR: mkdtempSync(join(tmpdir(), "vl-main-")) },
    stdio: "ignore",
  });
  t.after(() => child.kill());
  const base = `http://127.0.0.1:${port}/v1`;
  for (let i = 0; i < 100; i++) {
    if (await fetch(`${base}/health`).then((r) => r.ok, () => false)) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  // Without a session each group answers 401, which proves it is registered (404 would mean missing).
  for (const path of ["/me", "/conversations", "/figures", "/sync", "/admin/routing"]) {
    assert.equal((await fetch(`${base}${path}`)).status, 401, path);
  }
});
