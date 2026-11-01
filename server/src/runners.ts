import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ModelRegistry } from "./models.ts";

export const STAGES = ["research", "design", "parts", "geometry", "build", "motion", "verify", "polish"] as const;
export type Stage = (typeof STAGES)[number];

export interface FigureJob {
  id: string;
  prompt: string;
  theme: "dark" | "light";
  /** OpenCode session to resume, from an earlier attempt. */
  sessionId: string | null;
}

export interface FigureResult {
  title: string;
  summary: string;
  dark: Buffer;
  light: Buffer;
}

export interface RunHooks {
  progress(stage: Stage, message: string): void;
  /** The agent session id, saved so a retry can resume it. */
  session(id: string): void;
  signal: AbortSignal;
}

/** Turns a prompt into finished figure pages. */
export interface FigureRunner {
  run(job: FigureJob, hooks: RunHooks): Promise<FigureResult>;
}

const here = dirname(fileURLToPath(import.meta.url));

/** Development runner: walks through the stages and returns Anatomy's dial-indicator example (MIT). */
export class DevRunner implements FigureRunner {
  private readonly stageMs: number;

  constructor(stageMs: number) {
    this.stageMs = stageMs;
  }

  async run(job: FigureJob, hooks: RunHooks): Promise<FigureResult> {
    const messages: Record<Stage, string> = {
      research: "Collecting the real sizes and readings.",
      design: "Choosing a machine whose working is the idea.",
      parts: "Writing the parts list.",
      geometry: "Building the base, stand and carriage.",
      build: "Writing the page.",
      motion: "Adding the motion and live readout.",
      verify: "Checking joints and line ends in screenshots.",
      polish: "Final touches.",
    };
    for (const stage of STAGES) {
      hooks.progress(stage, messages[stage]);
      await sleep(this.stageMs, hooks.signal);
    }
    const sample = join(here, "..", "assets", "sample");
    return {
      title: "Dial indicator on gauge blocks",
      summary: `Example figure from the development runner (asked for: "${job.prompt.slice(0, 80)}"). A dial gauge reads three gauge blocks as the carriage slides under its plunger.`,
      dark: readFileSync(join(sample, "dark.html")),
      light: readFileSync(join(sample, "light.html")),
    };
  }
}

function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) return reject(signal.reason);
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => (clearTimeout(timer), reject(signal.reason)), { once: true });
  });
}

// --- OpenCode ---

export interface OpenCodeOptions {
  /** The opencode executable. */
  bin: string;
  /** A checkout of the Anatomy skill (github.com/wheresryan22/anatomy, pinned to a commit). */
  anatomyDir: string;
  /** Where per-job folders go. Must sit under this server's folder so tools resolve @opencode-ai/plugin. */
  jobsDir: string;
  timeoutMs: number;
}

/**
 * Runs Anatomy with OpenCode, one isolated config and work folder per job (section 11).
 * The model comes from the admin's figure routing; its fallback is tried when the
 * primary fails before reporting any progress.
 */
export class OpenCodeRunner implements FigureRunner {
  private readonly options: OpenCodeOptions;
  private readonly models: ModelRegistry;

  constructor(options: OpenCodeOptions, models: ModelRegistry) {
    this.options = options;
    this.models = models;
  }

  async run(job: FigureJob, hooks: RunHooks): Promise<FigureResult> {
    const route = this.models.routing().figures;
    const candidates = [route.model, route.fallback].filter((m): m is string => !!m);
    let lastError: unknown;
    for (const model of candidates) {
      let progressed = false;
      try {
        return await this.attempt(job, model, { ...hooks, progress: (s, m) => ((progressed = true), hooks.progress(s, m)) });
      } catch (e) {
        if (progressed || hooks.signal.aborted) throw e;
        lastError = e;
      }
    }
    throw lastError ?? new Error("No figure model configured");
  }

  private async attempt(job: FigureJob, model: string, hooks: RunHooks): Promise<FigureResult> {
    const dir = join(this.options.jobsDir, job.id);
    const config = join(dir, "config", "opencode");
    const work = join(dir, "work");
    const out = join(dir, "out");
    for (const d of [join(config, "tools"), work, out]) mkdirSync(d, { recursive: true });
    rmSync(join(dir, "result.json"), { force: true });
    rmSync(join(dir, "progress.jsonl"), { force: true });

    const skill = join(config, "skills", "anatomy");
    if (!existsSync(skill)) cpSync(this.options.anatomyDir, skill, { recursive: true, filter: (p) => !/[\\/]\.git([\\/]|$)/.test(p) });
    writeFileSync(join(config, "tools", "report_progress.ts"), REPORT_PROGRESS_TOOL);
    writeFileSync(join(config, "tools", "finish.ts"), FINISH_TOOL);
    const { json, env } = openCodeConfig(this.models, model);
    writeFileSync(join(config, "opencode.json"), JSON.stringify(json, null, 2));

    const args = ["run", "--format", "json", "--auto", "--model", model];
    if (job.sessionId) args.push("--session", job.sessionId, "Continue where you stopped. Finish the figure and call finish.");
    else args.push(figureTask(job));

    const child = spawn(this.options.bin, args, {
      cwd: work,
      // stdin closed, or `opencode run` waits for input.
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...env, XDG_CONFIG_HOME: join(dir, "config"), VL_JOB_DIR: dir },
      shell: process.platform === "win32",
    });

    let progressSeen = 0;
    const poll = setInterval(() => {
      const file = join(dir, "progress.jsonl");
      if (!existsSync(file)) return;
      const lines = readFileSync(file, "utf8").split("\n").filter(Boolean);
      for (const line of lines.slice(progressSeen)) {
        const { stage, message } = JSON.parse(line) as { stage: Stage; message: string };
        if (STAGES.includes(stage)) hooks.progress(stage, String(message).slice(0, 300));
      }
      progressSeen = lines.length;
    }, 1000);

    let stdout = "";
    child.stdout.on("data", (chunk: Buffer) => {
      stdout += chunk.toString();
      const lines = stdout.split("\n");
      stdout = lines.pop() ?? "";
      for (const line of lines) {
        const id = /"sessionI[Dd]"\s*:\s*"([^"]+)"/.exec(line)?.[1];
        if (id) hooks.session(id);
      }
    });
    let stderr = "";
    child.stderr.on("data", (chunk: Buffer) => (stderr = (stderr + chunk.toString()).slice(-4000)));

    const kill = () => child.kill("SIGTERM");
    hooks.signal.addEventListener("abort", kill, { once: true });
    const timeout = setTimeout(kill, this.options.timeoutMs);
    const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
    clearTimeout(timeout);
    clearInterval(poll);
    hooks.signal.removeEventListener("abort", kill);
    if (hooks.signal.aborted) throw hooks.signal.reason;

    const resultFile = join(dir, "result.json");
    if (!existsSync(resultFile)) throw new Error(`opencode exited ${code} without finishing: ${stderr.trim().slice(-500)}`);
    const result = JSON.parse(readFileSync(resultFile, "utf8")) as { title: string; summary: string };
    return {
      title: String(result.title).slice(0, 80),
      summary: String(result.summary).slice(0, 1000),
      dark: readFileSync(join(out, "dark.html")),
      light: readFileSync(join(out, "light.html")),
    };
  }
}

/** The opencode.json for one job, and the environment that carries its API key. */
export function openCodeConfig(models: ModelRegistry, model: string) {
  const providerId = model.slice(0, model.indexOf("/"));
  const modelName = model.slice(model.indexOf("/") + 1);
  const p = models.provider(providerId);
  if (!p) throw new Error(`Unknown provider ${providerId}`);
  const keyVar = `VL_KEY_${p.id.toUpperCase().replace(/[^A-Z0-9]/g, "_")}`;
  const options: Record<string, string> = {};
  if (p.baseUrl) options.baseURL = p.baseUrl;
  if (p.apiKey) options.apiKey = `{env:${keyVar}}`;
  return {
    json: {
      $schema: "https://opencode.ai/config.json",
      model,
      autoupdate: false,
      share: "disabled",
      provider: { [p.id]: { npm: p.npm, name: p.id, options, models: { [modelName]: {} } } },
    },
    env: p.apiKey ? { [keyVar]: p.apiKey } : {},
  };
}

function figureTask(job: FigureJob) {
  return [
    "Use the anatomy skill to build an interactive figure for this request from a VisualLearn learner:",
    "",
    job.prompt,
    "",
    "No one is watching the terminal and you can't ask questions: make sensible choices and mention them in the summary.",
    "Build BOTH themes as standalone HTML pages (a dark page and a light page).",
    `The learner's app prefers the ${job.theme} theme.`,
    "Call report_progress at each workflow step (research, design, parts, geometry, build, motion, verify, polish).",
    "When both pages are built and checked, call finish with their paths, a short title and a two or three sentence summary.",
  ].join("\n");
}

const REPORT_PROGRESS_TOOL = `import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { tool } from "@opencode-ai/plugin";

export default tool({
  description: "Tell the learner what stage the figure is at. Call it when you start each workflow step.",
  args: {
    stage: tool.schema.enum(["research", "design", "parts", "geometry", "build", "motion", "verify", "polish"]),
    message: tool.schema.string().describe("One short sentence about what you are doing, for the learner"),
  },
  async execute(args) {
    appendFileSync(join(process.env.VL_JOB_DIR!, "progress.jsonl"), JSON.stringify(args) + "\\n");
    return "Reported.";
  },
});
`;

const FINISH_TOOL = `import { copyFileSync, writeFileSync } from "node:fs";
import { isAbsolute, join } from "node:path";
import { tool } from "@opencode-ai/plugin";

export default tool({
  description: "Deliver the finished figure. Call once, after both pages are built and checked.",
  args: {
    title: tool.schema.string().describe("Short title, at most 60 characters"),
    summary: tool.schema.string().describe("Two or three sentences: what the object is and what it explains"),
    dark_page: tool.schema.string().describe("Path of the built dark-theme standalone HTML page"),
    light_page: tool.schema.string().describe("Path of the built light-theme standalone HTML page"),
  },
  async execute(args, context) {
    const dir = process.env.VL_JOB_DIR!;
    const resolve = (p: string) => (isAbsolute(p) ? p : join(context.directory, p));
    copyFileSync(resolve(args.dark_page), join(dir, "out", "dark.html"));
    copyFileSync(resolve(args.light_page), join(dir, "out", "light.html"));
    writeFileSync(join(dir, "result.json"), JSON.stringify({ title: args.title, summary: args.summary }));
    return "Delivered. You are done; stop now.";
  },
});
`;

/** Follows the admin's figure routing: `dev/…` models use the development runner, anything else OpenCode. */
export class RoutedRunner implements FigureRunner {
  private readonly models: ModelRegistry;
  private readonly dev: FigureRunner;
  private readonly openCode: FigureRunner | null;

  constructor(models: ModelRegistry, dev: FigureRunner, openCode: FigureRunner | null) {
    this.models = models;
    this.dev = dev;
    this.openCode = openCode;
  }

  run(job: FigureJob, hooks: RunHooks): Promise<FigureResult> {
    if (this.models.routing().figures.model.startsWith("dev/")) return this.dev.run(job, hooks);
    if (!this.openCode) return Promise.reject(new Error("Figure routing names a real model, but VL_ANATOMY_DIR isn't set, so OpenCode can't run"));
    return this.openCode.run(job, hooks);
  }
}
