import assert from "node:assert";
import * as fs from "node:fs/promises";
import * as os from "node:os";
import * as path from "node:path";
import { after, before, describe, it } from "node:test";

import type { AgentTool } from "@earendil-works/pi-agent-core";
import { resolveSourcePath } from "../src/agent/tools/fs-shared.ts";
import { lsTool } from "../src/agent/tools/ls.ts";
import { readTool } from "../src/agent/tools/read.ts";

/**
 * Hosted confinement (VisualLearn fork): with LH_FILES_ROOT set, the learner
 * tools behave as if that folder were home and refuse everything outside it.
 *
 * Fixture (tmpdir):
 *   root/notes/engine.md     the learner's file
 *   outside/secret.txt       another learner's file / the server's own
 *   root/escape              symbolic link to ../outside (when the OS allows)
 */

let BASE: string;
let ROOT: string;
let OUTSIDE: string;
let linked = false;
const saved = process.env.LH_FILES_ROOT;

async function runText(tool: AgentTool<any>, params: unknown): Promise<{ text: string }> {
	const result = await tool.execute!("c", params);
	return { text: (result.content[0] as { type: string; text: string }).text };
}

before(async () => {
	BASE = await fs.mkdtemp(path.join(os.tmpdir(), "lh-files-root-"));
	ROOT = path.join(BASE, "root");
	OUTSIDE = path.join(BASE, "outside");
	await fs.mkdir(path.join(ROOT, "notes"), { recursive: true });
	await fs.mkdir(OUTSIDE, { recursive: true });
	await fs.writeFile(path.join(ROOT, "notes", "engine.md"), "Four strokes: intake, compression, power, exhaust.");
	await fs.writeFile(path.join(OUTSIDE, "secret.txt"), "not yours");
	try {
		await fs.symlink(OUTSIDE, path.join(ROOT, "escape"), "dir");
		linked = true;
	} catch {
		// Windows without symlink rights: the link case is skipped.
	}
	process.env.LH_FILES_ROOT = ROOT;
});

after(async () => {
	if (saved === undefined) delete process.env.LH_FILES_ROOT;
	else process.env.LH_FILES_ROOT = saved;
	await fs.rm(BASE, { recursive: true, force: true });
});

describe("LH_FILES_ROOT confines the learner tools", () => {
	it("anchors absent, relative and tilde paths at the folder instead of home", () => {
		assert.strictEqual(resolveSourcePath(), ROOT);
		assert.strictEqual(resolveSourcePath("notes/engine.md"), path.join(ROOT, "notes", "engine.md"));
		assert.strictEqual(resolveSourcePath("~/notes"), path.join(ROOT, "notes"));
		assert.strictEqual(resolveSourcePath(path.join(ROOT, "notes")), path.join(ROOT, "notes"));
	});

	it("refuses absolute paths, parent steps and home outside the folder", () => {
		assert.throws(() => resolveSourcePath(path.join(OUTSIDE, "secret.txt")), /outside them/);
		assert.throws(() => resolveSourcePath("../outside/secret.txt"), /outside them/);
		assert.throws(() => resolveSourcePath("notes/../../outside"), /outside them/);
		assert.throws(() => resolveSourcePath(os.homedir()), /outside them/);
	});

	it("refuses a symbolic link that leads out of the folder", (t) => {
		if (!linked) return t.skip("this OS user can't create symbolic links");
		assert.throws(() => resolveSourcePath("escape/secret.txt"), /outside them/);
		assert.throws(() => resolveSourcePath("escape"), /outside them/);
	});

	it("still allows paths inside that don't exist yet, so tools report them missing", () => {
		assert.strictEqual(resolveSourcePath("notes/new.md"), path.join(ROOT, "notes", "new.md"));
	});

	// Tools throw, as they do for a missing path; the agent loop hands the
	// message to the model as an error result.
	it("the read and ls tools refuse instead of returning the file", async () => {
		await assert.rejects(readTool.execute!("c", { path: path.join(OUTSIDE, "secret.txt") }), /outside them/);
		await assert.rejects(lsTool.execute!("c", { path: OUTSIDE }), /outside them/);
		const inside = await runText(readTool, { path: "notes/engine.md" });
		assert.match(inside.text, /Four strokes/);
	});
});

describe("without LH_FILES_ROOT nothing changes", () => {
	it("resolves against home exactly as upstream", () => {
		const current = process.env.LH_FILES_ROOT;
		delete process.env.LH_FILES_ROOT;
		try {
			assert.strictEqual(resolveSourcePath(), os.homedir());
			assert.strictEqual(resolveSourcePath("notes"), path.join(os.homedir(), "notes"));
			assert.strictEqual(resolveSourcePath(path.join(OUTSIDE, "secret.txt")), path.join(OUTSIDE, "secret.txt"));
		} finally {
			process.env.LH_FILES_ROOT = current;
		}
	});
});
