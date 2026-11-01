import type { FastifyInstance } from "fastify";
import type { Deps } from "./app.ts";
import { summaryJson } from "./chat.ts";
import { badRequest } from "./errors.ts";
import type { FigureRow, Figures } from "./figures.ts";

/**
 * GET /sync (section 7): everything that changed since a cursor, including deletions made
 * on another device or by an admin. The cursor is opaque to the app; here it is a time in
 * ms. Changes stamped in the cursor's own millisecond are sent again rather than missed,
 * which is harmless because the app applies them idempotently.
 */
export function syncRoutes(app: FastifyInstance, deps: Deps, figures: Figures | null) {
  app.get("/sync", async (request) => {
    const raw = (request.query as { since?: string } | undefined)?.since;
    const since = raw === undefined ? -Infinity : Number(raw);
    if (Number.isNaN(since)) throw badRequest("That sync cursor isn't valid.");
    const cursor = deps.now();
    const userId = request.user.id;
    const after = Number.isFinite(since) ? since : -1;

    const conversations = deps.db
      .prepare("SELECT * FROM conversations WHERE user_id = ? AND updated_at >= ? ORDER BY updated_at")
      .all(userId, after) as unknown as Parameters<typeof summaryJson>[0][];
    const deleted = deps.db
      .prepare("SELECT kind, item_id FROM deletions WHERE user_id = ? AND deleted_at >= ?")
      .all(userId, after) as { kind: string; item_id: string }[];
    const figureRows = figures
      ? (deps.db.prepare("SELECT * FROM figures WHERE user_id = ? AND updated_at >= ? ORDER BY updated_at").all(userId, after) as unknown as FigureRow[])
      : [];
    const queue = figures?.queueOrder() ?? [];

    return {
      conversations: conversations.map(({ id, title, updated_at }) => ({ id, title, updatedAt: new Date(updated_at).toISOString() })),
      deletedConversationIds: deleted.filter((d) => d.kind === "conversation").map((d) => d.item_id),
      figures: figureRows.map((f) => figures!.toJson(f, queue)),
      deletedFigureIds: deleted.filter((d) => d.kind === "figure").map((d) => d.item_id),
      cursor: String(cursor),
    };
  });
}
