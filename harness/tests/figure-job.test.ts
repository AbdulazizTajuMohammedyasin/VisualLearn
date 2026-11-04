import assert from "node:assert";
import { describe, it } from "node:test";

import { buildSubagentSteer, isSubagentFailure, type FigureResult } from "../src/core/contracts.ts";
import { buildFigure, figureServiceAvailable } from "../src/agent/subagents/figure-client.ts";
import { jobTools } from "../src/agent/tools/jobs.ts";

/**
 * VisualLearn fork: the `figure` job asks the hosting server for a 3D Anatomy
 * figure and follows it. These tests use a scripted fetch, never a network.
 */

const ENV = { VL_FIGURE_URL: "http://127.0.0.1:8080/v1/internal/", VL_FIGURE_TOKEN: "usr_1.sig" };

/** A fetch that answers from a script of server figure states and records calls. */
function scriptedFetch(states: object[]) {
	const calls: { method: string; url: string; headers: Record<string, string>; body?: string }[] = [];
	let i = 0;
	const fetchImpl = (async (url: string, init: { method: string; headers: Record<string, string>; body?: string }) => {
		calls.push({ method: init.method, url, headers: init.headers, body: init.body });
		const state = init.method === "POST" && url.endsWith("/cancel") ? { id: "fig_1", status: "cancelled" } : states[Math.min(i++, states.length - 1)];
		return new Response(JSON.stringify(state), { status: 200, headers: { "content-type": "application/json" } });
	}) as unknown as typeof fetch;
	return { fetchImpl, calls };
}

const base = { stage: null, message: null, queuePosition: null, title: null, summary: null, error: null };

describe("figure job (VisualLearn fork)", () => {
	it("the tool is offered only when the server provides a figure service", () => {
		const ctx = { chatId: "c", jobStore: {} as never, spawn: () => {} };
		const saved = { ...process.env };
		try {
			delete process.env.VL_FIGURE_URL;
			delete process.env.VL_FIGURE_TOKEN;
			assert.strictEqual(figureServiceAvailable(), false);
			assert.ok(!("figure" in jobTools(ctx)));
			Object.assign(process.env, ENV);
			assert.ok("figure" in jobTools(ctx));
		} finally {
			process.env.VL_FIGURE_URL = saved.VL_FIGURE_URL;
			process.env.VL_FIGURE_TOKEN = saved.VL_FIGURE_TOKEN;
			if (saved.VL_FIGURE_URL === undefined) delete process.env.VL_FIGURE_URL;
			if (saved.VL_FIGURE_TOKEN === undefined) delete process.env.VL_FIGURE_TOKEN;
		}
	});

	it("asks the server for the figure, follows its stages and returns its id", async () => {
		const { fetchImpl, calls } = scriptedFetch([
			{ ...base, id: "fig_1", status: "queued", queuePosition: 2 },
			{ ...base, id: "fig_1", status: "running", stage: "parts", message: "Writing the parts list." },
			{ ...base, id: "fig_1", status: "succeeded", title: "One cylinder", summary: "Piston, rod and crank turning." },
		]);
		const activity: string[] = [];
		const result = await buildFigure(
			{ title: "Four-stroke engine", brief: "One cylinder with valves." },
			{ signal: new AbortController().signal, onActivity: (l) => activity.push(l), pollMs: 1, env: ENV, fetchImpl },
		);
		assert.deepStrictEqual(result, { figureId: "fig_1", title: "One cylinder", summary: "Piston, rod and crank turning." });
		const [create, poll] = calls;
		assert.ok(create && poll);
		assert.strictEqual(create.method, "POST");
		assert.strictEqual(create.url, "http://127.0.0.1:8080/v1/internal/figures");
		assert.strictEqual(create.headers["x-visuallearn-learner"], "usr_1.sig");
		assert.deepStrictEqual(JSON.parse(create.body!), { title: "Four-stroke engine", prompt: "Four-stroke engine. One cylinder with valves." });
		assert.strictEqual(poll.url, "http://127.0.0.1:8080/v1/internal/figures/fig_1");
		assert.deepStrictEqual(activity, ["waiting for a worker (2 in line)", "building: parts — Writing the parts list."]);
	});

	it("a failed build comes back as the failure shape with the server's reason", async () => {
		const { fetchImpl } = scriptedFetch([
			{ ...base, id: "fig_1", status: "running", stage: "research" },
			{ ...base, id: "fig_1", status: "failed", error: { message: "The AI service has reached its usage limit for now." } },
		]);
		const result = await buildFigure({ title: "Lock", brief: "Pins." }, { signal: new AbortController().signal, pollMs: 1, env: ENV, fetchImpl });
		assert.strictEqual(result.figureId, "");
		assert.match(result.notes!, /^FAILED: The AI service has reached its usage limit/);
		assert.ok(isSubagentFailure("figure", result));
	});

	it("aborting the job cancels the figure on the server", async () => {
		const { fetchImpl, calls } = scriptedFetch([{ ...base, id: "fig_1", status: "running", stage: "build" }]);
		const abort = new AbortController();
		const running = buildFigure({ title: "Gears", brief: "Two gears." }, { signal: abort.signal, pollMs: 50, env: ENV, fetchImpl });
		await new Promise((r) => setTimeout(r, 10));
		abort.abort(new Error("Aborted by learner"));
		await assert.rejects(running);
		await new Promise((r) => setTimeout(r, 10));
		assert.ok(calls.some((c) => c.method === "POST" && c.url.endsWith("/figures/fig_1/cancel")));
	});

	it("without the service settings the job fails cleanly instead of calling anything", async () => {
		const { fetchImpl, calls } = scriptedFetch([]);
		const result = await buildFigure({ title: "Heart", brief: "Valves." }, { signal: new AbortController().signal, env: {}, fetchImpl });
		assert.strictEqual(result.figureId, "");
		assert.strictEqual(calls.length, 0);
	});

	it("the teacher is told to teach with a finished figure, or to fall back when it failed", () => {
		const job = { id: "job_1", kind: "figure" as const, toolCallId: "call_1" };
		const ok: FigureResult = { figureId: "fig_1", title: "One cylinder", summary: "Piston and crank." };
		const done = buildSubagentSteer(job, ok);
		assert.match(String(done.content), /3D figure "One cylinder" is built[\s\S]*What it shows: Piston and crank\.[\s\S]*what to try/);
		assert.deepStrictEqual((done.details as { result: FigureResult }).result, ok);
		const failed = buildSubagentSteer(job, { figureId: "", title: "One cylinder", notes: "FAILED: quota" });
		assert.match(String(failed.content), /could not be built: FAILED: quota[\s\S]*prose, a demo or a visual/);
	});
});
