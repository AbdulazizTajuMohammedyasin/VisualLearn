import { EventEmitter } from "node:events";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import type { ModelMessage } from "ai";
import type { Deps } from "./app.ts";
import { transaction } from "./db.ts";
import { badRequest, notFound } from "./errors.ts";
import { FenceSplitter } from "./fence.ts";
import { runChat } from "./models.ts";
import { iso, newId } from "./util.ts";

export type Part =
  | { type: "text"; text: string }
  | { type: "ui"; program: string; libraryVersion: string }
  | { type: "figure"; figureId: string };

interface MessageRow {
  id: string;
  conversation_id: string;
  user_id: string;
  role: "user" | "assistant";
  status: "streaming" | "complete" | "failed";
  parts_json: string;
  error_json: string | null;
  created_at: number;
}

interface ConversationRow {
  id: string;
  user_id: string;
  title: string;
  created_at: number;
  updated_at: number;
}

export interface UiAction {
  sourceMessageId: string;
  name: string;
  context: Record<string, unknown>;
}

const NEW_TITLE = "New chat";
const PING_MS = 15_000;

export const messageJson = (m: MessageRow) => ({
  id: m.id,
  role: m.role,
  status: m.status,
  createdAt: iso(m.created_at),
  parts: JSON.parse(m.parts_json) as Part[],
  error: m.error_json ? JSON.parse(m.error_json) : null,
});

export const summaryJson = (c: ConversationRow) => ({ id: c.id, title: c.title, createdAt: iso(c.created_at), updatedAt: iso(c.updated_at) });

/**
 * Runs assistant replies. Every event is stored before it is announced, so a client
 * reconnecting with Last-Event-ID gets exactly what it missed, even after a restart.
 */
export class ChatEngine {
  private readonly deps: Deps;
  private readonly live = new Map<string, { events: EventEmitter; abort: AbortController }>();

  constructor(deps: Deps) {
    this.deps = deps;
    // Replies cut off by a restart can't resume; end them honestly.
    const interrupted = deps.db.prepare("SELECT id FROM messages WHERE status = 'streaming'").all() as { id: string }[];
    for (const { id } of interrupted) {
      this.finish(id, null, { code: "interrupted", message: "This reply was interrupted. Try again.", retryable: true });
    }
  }

  isLive(messageId: string) {
    return this.live.has(messageId);
  }

  subscribe(messageId: string, listener: (seq: number, event: string, data: unknown) => void): () => void {
    const entry = this.live.get(messageId);
    if (!entry) return () => {};
    entry.events.on("event", listener);
    return () => entry.events.off("event", listener);
  }

  stop(messageId: string) {
    this.live.get(messageId)?.abort.abort();
  }

  /** Starts the reply for [assistantId] in the background. */
  start(conversation: ConversationRow, assistantId: string, mode: "auto" | "figure") {
    const { db } = this.deps;
    const abort = new AbortController();
    const events = new EventEmitter();
    this.live.set(assistantId, { events, abort });

    const parts: Part[] = [];
    let seq = 0;
    const send = (event: string, data: unknown) => {
      seq++;
      try {
        db.prepare("INSERT INTO message_events (message_id, seq, event, data_json) VALUES (?, ?, ?, ?)").run(assistantId, seq, event, JSON.stringify(data));
      } catch {
        // The conversation was deleted mid-reply: stop quietly.
        abort.abort();
        return;
      }
      events.emit("event", seq, event, data);
    };
    const startPart = (part: Part) => {
      parts.push(part);
      send("part.start", { partIndex: parts.length - 1, ...part });
    };

    const run = async () => {
      const splitter = new FenceSplitter();
      const apply = (fence: ReturnType<FenceSplitter["push"]>) => {
        for (const e of fence) {
          if (e.type === "text-start") startPart({ type: "text", text: "" });
          else if (e.type === "ui-start") startPart({ type: "ui", program: "", libraryVersion: this.deps.uiLibraryVersion });
          else {
            const index = parts.length - 1;
            const part = parts[index];
            if (e.type === "text" && part.type === "text") {
              part.text += e.text;
              send("text.delta", { partIndex: index, text: e.text });
            } else if (e.type === "ui" && part.type === "ui") {
              part.program += e.program;
              send("ui.delta", { partIndex: index, program: e.program });
            }
          }
        }
      };
      try {
        const routing = this.deps.models.routing();
        const turn = {
          system: this.deps.chatSystemPrompt,
          messages: this.history(conversation.id, assistantId),
          figureMode: mode === "figure",
          startFigure: this.deps.figures ? (prompt: string) => this.deps.figures!.create(conversation.user_id, prompt, conversation.id) : null,
          signal: abort.signal,
        };
        for await (const event of runChat(this.deps.models, routing.chat, turn)) {
          if (event.type === "text") apply(splitter.push(event.text));
          else {
            apply(splitter.end());
            startPart({ type: "figure", figureId: event.figureId });
          }
        }
        apply(splitter.end());
        this.finish(assistantId, parts, null, send);
      } catch (e) {
        apply(splitter.end());
        if (abort.signal.aborted) this.finish(assistantId, parts, null, send);
        else {
          this.deps.log(`chat reply ${assistantId} failed: ${(e as Error)?.message ?? e}`);
          this.finish(assistantId, parts, { code: "model_error", message: "The reply couldn't be finished. Try again.", retryable: true }, send);
        }
      } finally {
        this.live.delete(assistantId);
        events.emit("end");
      }
    };
    void run();
  }

  /** Stores the final message and announces it; [parts] null keeps what is stored. */
  private finish(
    messageId: string,
    parts: Part[] | null,
    error: { code: string; message: string; retryable: boolean } | null,
    send?: (event: string, data: unknown) => void,
  ) {
    const { db, now } = this.deps;
    transaction(db, () => {
      const status = error ? "failed" : "complete";
      if (parts) db.prepare("UPDATE messages SET status = ?, parts_json = ?, error_json = ? WHERE id = ?").run(status, JSON.stringify(parts), error ? JSON.stringify(error) : null, messageId);
      else db.prepare("UPDATE messages SET status = ?, error_json = ? WHERE id = ?").run(status, error ? JSON.stringify(error) : null, messageId);
      db.prepare("UPDATE conversations SET updated_at = ? WHERE id = (SELECT conversation_id FROM messages WHERE id = ?)").run(now(), messageId);
    });
    const row = db.prepare("SELECT * FROM messages WHERE id = ?").get(messageId) as MessageRow | undefined;
    if (!row) return;
    if (error) {
      if (send) send("message.error", { error });
      else this.appendStored(messageId, "message.error", { error });
    }
    if (send) send("message.done", { message: messageJson(row) });
    else this.appendStored(messageId, "message.done", { message: messageJson(row) });
  }

  private appendStored(messageId: string, event: string, data: unknown) {
    const { db } = this.deps;
    const next = (db.prepare("SELECT COALESCE(MAX(seq), 0) + 1 AS seq FROM message_events WHERE message_id = ?").get(messageId) as { seq: number }).seq;
    db.prepare("INSERT INTO message_events (message_id, seq, event, data_json) VALUES (?, ?, ?, ?)").run(messageId, next, event, JSON.stringify(data));
  }

  /** The conversation so far in model form, up to (not including) [beforeId]. */
  private history(conversationId: string, beforeId: string): ModelMessage[] {
    const rows = this.deps.db
      .prepare("SELECT * FROM messages WHERE conversation_id = ? AND id != ? ORDER BY created_at, rowid")
      .all(conversationId, beforeId) as unknown as (MessageRow & { ui_action_json: string | null })[];
    const out: ModelMessage[] = [];
    for (const m of rows.slice(-40)) {
      const parts = JSON.parse(m.parts_json) as Part[];
      if (m.role === "user") {
        let content = parts.map((p) => (p.type === "text" ? p.text : "")).join("");
        if (m.ui_action_json) {
          const action = JSON.parse(m.ui_action_json) as UiAction;
          content += `\n\n[interactive action: ${action.name}]\n${JSON.stringify(action.context)}`;
        }
        out.push({ role: "user", content });
      } else {
        const content = parts
          .map((p) => (p.type === "text" ? p.text : p.type === "ui" ? `\`\`\`openui\n${p.program}\n\`\`\`` : `[figure ${p.figureId} started]`))
          .join("\n\n");
        if (content.trim()) out.push({ role: "assistant", content });
      }
    }
    return out;
  }
}

export function chatRoutes(app: FastifyInstance, deps: Deps, engine: ChatEngine) {
  const { db, now } = deps;

  const ownConversation = (request: FastifyRequest, id: string) => {
    const c = db.prepare("SELECT * FROM conversations WHERE id = ?").get(id) as ConversationRow | undefined;
    if (!c || c.user_id !== request.user.id) throw notFound("This chat");
    return c;
  };

  const ownMessage = (request: FastifyRequest, id: string) => {
    const m = db.prepare("SELECT * FROM messages WHERE id = ?").get(id) as MessageRow | undefined;
    if (!m || m.user_id !== request.user.id) throw notFound("This message");
    return m;
  };

  app.get("/conversations", async (request) => {
    const { limit, offset } = page(request.query);
    const rows = db
      .prepare("SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC, id LIMIT ? OFFSET ?")
      .all(request.user.id, limit + 1, offset) as unknown as ConversationRow[];
    return { items: rows.slice(0, limit).map(summaryJson), nextCursor: rows.length > limit ? String(offset + limit) : null };
  });

  app.post("/conversations", async (request) => {
    const id = newId("conv");
    const t = now();
    db.prepare("INSERT INTO conversations (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)").run(id, request.user.id, NEW_TITLE, t, t);
    return summaryJson(ownConversation(request, id));
  });

  app.get("/conversations/:id", async (request) => {
    const c = ownConversation(request, (request.params as { id: string }).id);
    const messages = db.prepare("SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at, rowid").all(c.id) as unknown as MessageRow[];
    return { ...summaryJson(c), messages: messages.map(messageJson), nextCursor: null };
  });

  app.patch("/conversations/:id", async (request) => {
    const c = ownConversation(request, (request.params as { id: string }).id);
    const title = (request.body as { title?: unknown } | undefined)?.title;
    if (typeof title !== "string" || !title.trim()) throw badRequest("Give the chat a title.");
    db.prepare("UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?").run(title.trim().slice(0, 120), now(), c.id);
    return summaryJson(ownConversation(request, c.id));
  });

  app.delete("/conversations/:id", async (request, reply) => {
    const c = ownConversation(request, (request.params as { id: string }).id);
    deleteConversation(deps, engine, c.id);
    return reply.code(204).send();
  });

  app.post("/conversations/:id/messages", async (request) => {
    const c = ownConversation(request, (request.params as { id: string }).id);
    const body = (request.body ?? {}) as { text?: unknown; mode?: unknown; uiAction?: unknown };
    const text = typeof body.text === "string" ? body.text.trim() : "";
    const mode = body.mode === "figure" ? "figure" : "auto";
    const uiAction = parseUiAction(body.uiAction);
    if (!text && !uiAction) throw badRequest("Type a message first.");
    if (text.length > 20_000) throw badRequest("That message is too long.");
    const busy = db.prepare("SELECT 1 FROM messages WHERE conversation_id = ? AND status = 'streaming'").get(c.id);
    if (busy) throw badRequest("Wait for the current reply to finish.");

    const t = now();
    const userId = newId("msg");
    const assistantId = newId("msg");
    transaction(db, () => {
      const insert = db.prepare(
        "INSERT INTO messages (id, conversation_id, user_id, role, status, parts_json, error_json, ui_action_json, created_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
      );
      insert.run(userId, c.id, request.user.id, "user", "complete", JSON.stringify(text ? [{ type: "text", text }] : []), uiAction ? JSON.stringify(uiAction) : null, t);
      insert.run(assistantId, c.id, request.user.id, "assistant", "streaming", "[]", null, t + 1);
      const title = c.title === NEW_TITLE ? (text || NEW_TITLE).split("\n")[0].slice(0, 48) : c.title;
      db.prepare("UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?").run(title, t, c.id);
    });
    engine.start(c, assistantId, mode);
    const get = (id: string) => messageJson(db.prepare("SELECT * FROM messages WHERE id = ?").get(id) as unknown as MessageRow);
    return { userMessage: get(userId), assistantMessage: get(assistantId) };
  });

  app.post("/messages/:id/stop", async (request, reply) => {
    const m = ownMessage(request, (request.params as { id: string }).id);
    engine.stop(m.id);
    return reply.code(204).send();
  });

  app.get("/messages/:id/events", async (request, reply) => {
    const m = ownMessage(request, (request.params as { id: string }).id);
    const after = Number(request.headers["last-event-id"] ?? 0) || 0;
    streamEvents(request, reply, deps, engine, m.id, after);
  });
}

/** Replays stored events after [after], then follows live ones until the reply ends. */
function streamEvents(request: FastifyRequest, reply: FastifyReply, deps: Deps, engine: ChatEngine, messageId: string, after: number) {
  reply.hijack();
  const res = reply.raw;
  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive", "X-Accel-Buffering": "no" });
  let last = after;
  let closed = false;
  const write = (seq: number, event: string, data: unknown) => {
    if (closed || seq <= last) return;
    last = seq;
    res.write(`id: ${seq}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };
  const end = () => {
    if (closed) return;
    closed = true;
    clearInterval(ping);
    unsubscribe();
    res.end();
  };
  // Subscribe first so nothing falls between the replay and the live stream.
  const buffered: [number, string, unknown][] = [];
  let replaying = true;
  const unsubscribe = engine.subscribe(messageId, (seq, event, data) => {
    if (replaying) buffered.push([seq, event, data]);
    else write(seq, event, data);
    if (event === "message.done") end();
  });
  const ping = setInterval(() => res.write("event: ping\ndata: {}\n\n"), PING_MS);
  request.raw.on("close", end);

  const stored = deps.db
    .prepare("SELECT seq, event, data_json FROM message_events WHERE message_id = ? AND seq > ? ORDER BY seq")
    .all(messageId, after) as { seq: number; event: string; data_json: string }[];
  let done = false;
  for (const e of stored) {
    write(e.seq, e.event, JSON.parse(e.data_json));
    if (e.event === "message.done") done = true;
  }
  replaying = false;
  for (const [seq, event, data] of buffered) {
    write(seq, event, data);
    if (event === "message.done") done = true;
  }
  if (done || !engine.isLive(messageId)) end();
}

function parseUiAction(value: unknown): UiAction | null {
  if (!value || typeof value !== "object") return null;
  const a = value as Record<string, unknown>;
  if (typeof a.name !== "string" || typeof a.sourceMessageId !== "string") return null;
  const context = a.context && typeof a.context === "object" ? (a.context as Record<string, unknown>) : {};
  if (JSON.stringify(context).length > 20_000) throw badRequest("That action carried too much data.");
  return { sourceMessageId: a.sourceMessageId, name: a.name.slice(0, 64), context };
}

export function page(query: unknown) {
  const q = (query ?? {}) as { cursor?: string; limit?: string };
  const limit = Math.min(100, Math.max(1, Number(q.limit) || 50));
  const offset = Math.max(0, Number(q.cursor) || 0);
  return { limit, offset };
}

/** Deletes a conversation and records it so other devices drop it on their next sync. */
export function deleteConversation(deps: Deps, engine: ChatEngine, id: string) {
  const c = deps.db.prepare("SELECT user_id FROM conversations WHERE id = ?").get(id) as { user_id: string } | undefined;
  if (!c) return;
  const streaming = deps.db.prepare("SELECT id FROM messages WHERE conversation_id = ? AND status = 'streaming'").all(id) as { id: string }[];
  streaming.forEach(({ id: m }) => engine.stop(m));
  transaction(deps.db, () => {
    deps.db.prepare("DELETE FROM conversations WHERE id = ?").run(id);
    deps.db.prepare("INSERT INTO deletions (user_id, kind, item_id, deleted_at) VALUES (?, 'conversation', ?, ?)").run(c.user_id, id, deps.now());
  });
}
