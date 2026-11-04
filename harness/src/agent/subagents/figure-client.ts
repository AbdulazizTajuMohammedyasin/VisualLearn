import type { FigureResult, FigureToolInput } from "../../core/contracts.ts";

/**
 * VisualLearn fork: 3D figures are not built inside LearnHarness. The hosting
 * VisualLearn server owns a fair-share queue of Anatomy workers (OpenCode),
 * preview images and phone notifications, so the `figure` job asks it to build
 * one for this learner and follows it until it finishes.
 *
 * The server passes its address and this learner's credential in the
 * environment: VL_FIGURE_URL (e.g. http://127.0.0.1:8080/v1/internal) and
 * VL_FIGURE_TOKEN. Without them the `figure` tool isn't offered at all.
 */

export function figureServiceAvailable(env: Record<string, string | undefined> = process.env): boolean {
	return Boolean(env.VL_FIGURE_URL && env.VL_FIGURE_TOKEN);
}

/** The server's figure record, as much of it as the job needs. */
interface ServerFigure {
	id: string;
	status: "queued" | "running" | "succeeded" | "failed" | "cancelled";
	stage: string | null;
	message: string | null;
	queuePosition: number | null;
	title: string | null;
	summary: string | null;
	error: { message: string } | null;
}

export interface FigureClientOptions {
	signal: AbortSignal;
	onActivity?: (line: string) => void;
	/** How often to ask for progress; figures take tens of minutes. */
	pollMs?: number;
	env?: Record<string, string | undefined>;
	fetchImpl?: typeof fetch;
}

/** Asks the server for a figure and waits for it, reporting its stage as activity. */
export async function buildFigure(input: FigureToolInput, options: FigureClientOptions): Promise<FigureResult> {
	const env = options.env ?? process.env;
	const doFetch = options.fetchImpl ?? fetch;
	const base = (env.VL_FIGURE_URL ?? "").replace(/\/+$/, "");
	const headers = { "content-type": "application/json", "x-visuallearn-learner": env.VL_FIGURE_TOKEN ?? "" };
	const title = input.title.trim() || "3D figure";
	if (!figureServiceAvailable(env)) return { figureId: "", title, notes: "FAILED: this server has no figure service" };

	const call = async (method: string, path: string, body?: unknown): Promise<ServerFigure> => {
		const res = await doFetch(`${base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: options.signal });
		const json = (await res.json().catch(() => ({}))) as ServerFigure & { error?: { message?: string } };
		if (!res.ok) throw new Error(json.error?.message ?? `figure service answered ${res.status}`);
		return json;
	};

	let figure = await call("POST", "/figures", { title, prompt: `${title}. ${input.brief}` });
	// Cancelling the job cancels the figure, so it stops using a worker.
	const cancel = () => void call("POST", `/figures/${figure.id}/cancel`).catch(() => {});
	options.signal.addEventListener("abort", cancel, { once: true });
	try {
		let last = "";
		while (figure.status === "queued" || figure.status === "running") {
			const line =
				figure.status === "queued"
					? figure.queuePosition ? `waiting for a worker (${figure.queuePosition} in line)` : "waiting for a worker"
					: `building: ${figure.stage ?? "starting"}${figure.message ? ` — ${figure.message}` : ""}`;
			if (line !== last) options.onActivity?.((last = line));
			await sleep(options.pollMs ?? 5000, options.signal);
			figure = await call("GET", `/figures/${figure.id}`);
		}
	} finally {
		options.signal.removeEventListener("abort", cancel);
	}
	if (figure.status === "succeeded") {
		return { figureId: figure.id, title: figure.title ?? title, ...(figure.summary ? { summary: figure.summary } : {}) };
	}
	const reason = figure.status === "cancelled" ? "it was cancelled" : (figure.error?.message ?? "the build failed");
	return { figureId: "", title, notes: `FAILED: ${reason}` };
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal.aborted) return reject(signal.reason);
		const timer = setTimeout(resolve, ms);
		signal.addEventListener("abort", () => (clearTimeout(timer), reject(signal.reason)), { once: true });
	});
}
