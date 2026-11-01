import { createAnthropic } from "@ai-sdk/anthropic";
import { createGoogleGenerativeAI } from "@ai-sdk/google";
import { createOpenAI } from "@ai-sdk/openai";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { isStepCount, streamText, tool, type LanguageModel, type ModelMessage } from "ai";
import { z } from "zod";
import type { Db } from "./db.ts";

/** A model provider the admin configured (section 9). `dev` is built in and needs no key. */
export interface Provider {
  id: string;
  kind: "api" | "local";
  npm: string;
  baseUrl: string | null;
  apiKey: string | null;
  models: string[];
}

export interface Route {
  model: string;
  fallback: string | null;
}

export interface Routing {
  chat: Route;
  figures: Route;
}

export const SUPPORTED_NPM = ["@ai-sdk/anthropic", "@ai-sdk/openai", "@ai-sdk/google", "@ai-sdk/openai-compatible"] as const;

/** Until an admin sets routing, everything uses the built-in canned model. */
const DEFAULT_ROUTING: Routing = {
  chat: { model: "dev/canned", fallback: null },
  figures: { model: "dev/canned", fallback: null },
};

export class ModelRegistry {
  private readonly db: Db;

  constructor(db: Db) {
    this.db = db;
  }

  providers(): Provider[] {
    return (this.db.prepare("SELECT * FROM providers ORDER BY id").all() as unknown as ProviderRow[]).map(fromRow);
  }

  provider(id: string): Provider | null {
    const row = this.db.prepare("SELECT * FROM providers WHERE id = ?").get(id) as ProviderRow | undefined;
    return row ? fromRow(row) : null;
  }

  saveProvider(p: Provider) {
    this.db
      .prepare(
        `INSERT INTO providers (id, kind, npm, base_url, api_key, models_json) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET kind = excluded.kind, npm = excluded.npm, base_url = excluded.base_url,
           api_key = excluded.api_key, models_json = excluded.models_json`,
      )
      .run(p.id, p.kind, p.npm, p.baseUrl, p.apiKey, JSON.stringify(p.models));
  }

  deleteProvider(id: string) {
    this.db.prepare("DELETE FROM providers WHERE id = ?").run(id);
  }

  routing(): Routing {
    const row = this.db.prepare("SELECT json FROM routing WHERE id = 1").get() as { json: string } | undefined;
    return row ? (JSON.parse(row.json) as Routing) : DEFAULT_ROUTING;
  }

  saveRouting(routing: Routing) {
    this.db.prepare("INSERT INTO routing (id, json) VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET json = excluded.json").run(JSON.stringify(routing));
  }

  /** The AI SDK model for a `provider/model` id; null for the built-in dev model. */
  languageModel(modelId: string): LanguageModel | null {
    const slash = modelId.indexOf("/");
    const providerId = modelId.slice(0, slash);
    const model = modelId.slice(slash + 1);
    if (providerId === "dev") return null;
    const p = this.provider(providerId);
    if (!p) throw new Error(`Unknown provider ${providerId}`);
    const apiKey = p.apiKey ?? undefined;
    const baseURL = p.baseUrl ?? undefined;
    switch (p.npm) {
      case "@ai-sdk/anthropic":
        return createAnthropic({ apiKey, baseURL })(model);
      case "@ai-sdk/openai":
        return createOpenAI({ apiKey, baseURL })(model);
      case "@ai-sdk/google":
        return createGoogleGenerativeAI({ apiKey, baseURL })(model);
      case "@ai-sdk/openai-compatible":
        if (!baseURL) throw new Error(`Provider ${p.id} needs a baseUrl`);
        return createOpenAICompatible({ name: p.id, baseURL, apiKey })(model);
      default:
        throw new Error(`Unsupported provider package ${p.npm}`);
    }
  }
}

interface ProviderRow {
  id: string;
  kind: "api" | "local";
  npm: string;
  base_url: string | null;
  api_key: string | null;
  models_json: string;
}

const fromRow = (r: ProviderRow): Provider => ({
  id: r.id, kind: r.kind, npm: r.npm, baseUrl: r.base_url, apiKey: r.api_key, models: JSON.parse(r.models_json),
});

// --- running a chat turn ---

export type ChatEvent = { type: "text"; text: string } | { type: "figure"; figureId: string };

export interface ChatTurn {
  system: string;
  messages: ModelMessage[];
  /** Starts a figure job for the model's create_figure tool; null when figures are unavailable. */
  startFigure: ((prompt: string) => string) | null;
  /** The user pressed the Figure button: start a figure for their request. */
  figureMode: boolean;
  signal: AbortSignal;
}

/** Streams one assistant turn from [modelId], trying [fallback] if the primary fails before saying anything. */
export async function* runChat(registry: ModelRegistry, route: Route, turn: ChatTurn): AsyncGenerator<ChatEvent> {
  const candidates = [route.model, route.fallback].filter((m): m is string => !!m);
  let lastError: unknown;
  for (const modelId of candidates) {
    let produced = false;
    try {
      for await (const event of runOne(registry, modelId, turn)) {
        produced = true;
        yield event;
      }
      return;
    } catch (e) {
      if (produced || turn.signal.aborted) throw e;
      lastError = e;
    }
  }
  throw lastError ?? new Error("No model configured");
}

async function* runOne(registry: ModelRegistry, modelId: string, turn: ChatTurn): AsyncGenerator<ChatEvent> {
  const model = registry.languageModel(modelId);
  if (!model) {
    yield* devModel(turn);
    return;
  }
  // In figure mode the server starts the job itself, so the model only introduces it.
  if (turn.figureMode && turn.startFigure) {
    const last = turn.messages.at(-1);
    yield { type: "figure", figureId: turn.startFigure(typeof last?.content === "string" ? last.content : "") };
  }
  const figureEvents: ChatEvent[] = [];
  const result = streamText({
    model,
    system: turn.system,
    messages: turn.messages,
    abortSignal: turn.signal,
    stopWhen: isStepCount(3),
    // Errors arrive as "error" stream parts and are handled below (fallback or a failed message).
    onError: () => {},
    tools:
      turn.startFigure && !turn.figureMode
        ? {
            create_figure: tool({
              description:
                "Start building an interactive figure (an Anatomy machine the learner can operate) for an idea that is easier to understand by seeing it work. Takes 10-60 minutes; the app shows progress and notifies the learner.",
              inputSchema: z.object({ prompt: z.string().describe("What the figure should show, in one or two sentences") }),
              execute: async ({ prompt }) => {
                const figureId = turn.startFigure!(prompt);
                figureEvents.push({ type: "figure", figureId });
                return { figureId, status: "queued" };
              },
            }),
          }
        : undefined,
  });
  for await (const part of result.fullStream) {
    if (part.type === "text-delta") yield { type: "text", text: part.text };
    else if (part.type === "error") throw part.error;
    while (figureEvents.length) yield figureEvents.shift()!;
  }
  while (figureEvents.length) yield figureEvents.shift()!;
}

// --- built-in development model ---

const QUIZ = `\`\`\`openui
root = Quiz("Zippers", [q1, q2, q3])
q1 = MultipleChoice("What holds two zipper teeth together?", ["Magnets", "A bump on one tooth seated in the hollow of the next", "Glue on the tape", "Friction alone"], 1, "Each tooth has a bump on top and a hollow underneath; the slider presses one into the other.")
q2 = MultipleChoice("What does the slider's Y-shaped channel do?", ["Heats the teeth", "Guides the two rows together at the right angle", "Cuts loose threads", "Counts the teeth"], 1, "The channel brings the rows in at an angle so each bump can slip into the next hollow.")
q3 = MultipleChoice("Why doesn't a closed zipper pull apart sideways?", ["The teeth are interlocked in a chain", "The tape is elastic", "The slider is still holding them", "Static electricity"], 0, "Once seated, every tooth is locked by its neighbours on both sides.")
\`\`\``;

/** Canned replies so the whole system runs without any model key (VL_DEV_AUTH style development). */
async function* devModel(turn: ChatTurn): AsyncGenerator<ChatEvent> {
  const last = turn.messages.at(-1);
  const text = (typeof last?.content === "string" ? last.content : "").toLowerCase();
  const wantsFigure = turn.figureMode || ["show me", "figure", "visualize", "draw"].some((w) => text.includes(w));
  let reply: string;
  if (text.includes("[interactive action: review_mistakes]")) {
    reply = "Let's look at what tripped you up. Each wrong answer missed the same idea: the teeth lock because every bump sits in the next tooth's hollow, and the slider only guides them together.";
  } else if (wantsFigure && turn.startFigure) {
    reply = "Good one to see in motion. I'm building an interactive figure of it now; the card below shows its progress.";
  } else if (["quiz", "test me", "flashcard", "mcq"].some((w) => text.includes(w))) {
    reply = `Here's a short quiz. Answer each question to see the explanation.\n\n${QUIZ}\n\nTap **Explain my mistakes** at the end if any trip you up.`;
  } else {
    reply = "This is the development model on the VisualLearn server, so the answer is canned.\n\n**How it works.** An idea breaks into parts that each do one job. Ask me to *show* it for a figure, or say *quiz me* for a check.";
  }
  for (const word of reply.split(/(?<= )/)) {
    if (turn.signal.aborted) return;
    await new Promise((r) => setTimeout(r, 15));
    yield { type: "text", text: word };
  }
  if (wantsFigure && turn.startFigure) yield { type: "figure", figureId: turn.startFigure(text) };
}
