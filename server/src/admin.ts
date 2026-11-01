import type { FastifyInstance, FastifyRequest } from "fastify";
import { deleteUser, type Deps } from "./app.ts";
import { publicUser, type UserRow } from "./auth.ts";
import { deleteConversation, summaryJson, type ChatEngine } from "./chat.ts";
import { badRequest, conflict, forbidden, notFound } from "./errors.ts";
import type { FigureRow } from "./figures.ts";
import { SUPPORTED_NPM, type Provider, type Route, type Routing } from "./models.ts";
import { iso } from "./util.ts";

const MASK = "••••";

const maskProvider = (p: Provider) => ({ ...p, apiKey: p.apiKey ? MASK : null });

/** Admin API (section 9). Every call is written to the audit log. */
export function adminRoutes(app: FastifyInstance, deps: Deps, chat: ChatEngine) {
  const { db, models } = deps;

  app.addHook("onRequest", async (request) => {
    if (request.user.role !== "admin") throw forbidden();
  });

  app.addHook("onResponse", async (request, reply) => {
    db.prepare("INSERT INTO audit_log (admin_id, method, path, status, at) VALUES (?, ?, ?, ?, ?)").run(
      request.user.id, request.method, request.url.split("?")[0], reply.statusCode, deps.now(),
    );
  });

  // --- model routing ---

  app.get("/admin/providers", async () => ({ items: models.providers().map(maskProvider) }));

  app.put("/admin/providers/:id", async (request) => {
    const id = (request.params as { id: string }).id;
    if (!/^[a-z0-9][a-z0-9-]{0,39}$/.test(id) || id === "dev") throw badRequest("Provider ids are lowercase letters, digits and dashes; `dev` is reserved.");
    const body = (request.body ?? {}) as Partial<Provider>;
    if (body.kind !== "api" && body.kind !== "local") throw badRequest("kind must be api or local.");
    if (!SUPPORTED_NPM.includes(body.npm as (typeof SUPPORTED_NPM)[number])) throw badRequest(`npm must be one of ${SUPPORTED_NPM.join(", ")}.`);
    if (!Array.isArray(body.models) || !body.models.length || !body.models.every((m) => typeof m === "string" && m.length > 0)) {
      throw badRequest("List the provider's models.");
    }
    const baseUrl = typeof body.baseUrl === "string" && body.baseUrl ? body.baseUrl : null;
    if (baseUrl && !/^https?:\/\//.test(baseUrl)) throw badRequest("baseUrl must be an http(s) URL.");
    if (body.kind === "local" && !baseUrl) throw badRequest("A local provider needs its baseUrl.");
    // Keys are write-only: the mask (or no key) keeps the stored one.
    const existing = models.provider(id);
    const apiKey = typeof body.apiKey === "string" && body.apiKey && body.apiKey !== MASK ? body.apiKey : (existing?.apiKey ?? null);
    const provider: Provider = { id, kind: body.kind, npm: body.npm!, baseUrl, apiKey, models: body.models };
    models.saveProvider(provider);
    return maskProvider(provider);
  });

  app.delete("/admin/providers/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    const routing = models.routing();
    const used = [routing.chat, routing.figures].some((r) => [r.model, r.fallback].some((m) => m?.startsWith(`${id}/`)));
    if (used) throw conflict("Routing still uses this provider. Change the routing first.");
    models.deleteProvider(id);
    return reply.code(204).send();
  });

  app.get("/admin/routing", async () => models.routing());

  app.put("/admin/routing", async (request) => {
    const body = (request.body ?? {}) as Partial<Routing>;
    const check = (name: string, route: Partial<Route> | undefined): Route => {
      if (!route || typeof route.model !== "string") throw badRequest(`${name}.model is required.`);
      const fallback = typeof route.fallback === "string" && route.fallback ? route.fallback : null;
      for (const model of [route.model, fallback]) {
        if (!model || model.startsWith("dev/")) continue;
        const [providerId, ...rest] = model.split("/");
        const p = models.provider(providerId);
        if (!p) throw badRequest(`${name}: no provider called ${providerId}.`);
        if (!p.models.includes(rest.join("/"))) throw badRequest(`${name}: ${providerId} doesn't list the model ${rest.join("/")}.`);
      }
      return { model: route.model, fallback };
    };
    const routing: Routing = { chat: check("chat", body.chat), figures: check("figures", body.figures) };
    models.saveRouting(routing);
    return routing;
  });

  // --- accounts and content ---

  const userById = (request: FastifyRequest) => {
    const u = db.prepare("SELECT * FROM users WHERE id = ?").get((request.params as { id: string }).id) as UserRow | undefined;
    if (!u) throw notFound("This account");
    return u;
  };

  app.get("/admin/users", async (request) => {
    const q = `%${((request.query as { query?: string }).query ?? "").trim().toLowerCase()}%`;
    const rows = db
      .prepare("SELECT * FROM users WHERE lower(COALESCE(email, '')) LIKE ? OR lower(display_name) LIKE ? ORDER BY created_at DESC LIMIT 100")
      .all(q, q) as unknown as UserRow[];
    return { items: rows.map((u) => ({ ...publicUser(u), createdAt: iso(u.created_at) })) };
  });

  app.get("/admin/users/:id", async (request) => {
    const u = userById(request);
    const count = (sql: string) => (db.prepare(sql).get(u.id) as { n: number }).n;
    const last = db
      .prepare("SELECT MAX(t) AS t FROM (SELECT MAX(updated_at) AS t FROM conversations WHERE user_id = ?1 UNION ALL SELECT MAX(updated_at) FROM figures WHERE user_id = ?1)")
      .get(u.id) as { t: number | null };
    return {
      ...publicUser(u),
      createdAt: iso(u.created_at),
      lastActiveAt: iso(last.t),
      conversations: count("SELECT COUNT(*) AS n FROM conversations WHERE user_id = ?"),
      figures: count("SELECT COUNT(*) AS n FROM figures WHERE user_id = ?"),
    };
  });

  app.delete("/admin/users/:id", async (request, reply) => {
    const u = userById(request);
    if (u.id === request.user.id) throw conflict("Delete your own account from the app instead.");
    deleteUser(deps, u.id);
    return reply.code(204).send();
  });

  app.get("/admin/users/:id/conversations", async (request) => {
    const u = userById(request);
    const rows = db.prepare("SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC LIMIT 200").all(u.id) as unknown as Parameters<typeof summaryJson>[0][];
    return { items: rows.map(summaryJson) };
  });

  app.delete("/admin/conversations/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    if (!db.prepare("SELECT 1 FROM conversations WHERE id = ?").get(id)) throw notFound("This chat");
    deleteConversation(deps, chat, id);
    return reply.code(204).send();
  });

  app.get("/admin/figures", async (request) => {
    const status = (request.query as { status?: string }).status;
    const rows = (
      status
        ? db.prepare("SELECT * FROM figures WHERE status = ? ORDER BY created_at DESC LIMIT 200").all(status)
        : db.prepare("SELECT * FROM figures ORDER BY created_at DESC LIMIT 200").all()
    ) as unknown as FigureRow[];
    const queue = deps.figures?.queueOrder() ?? [];
    return { items: rows.map((f) => ({ ...deps.figures!.toJson(f, queue), userId: f.user_id })) };
  });

  app.delete("/admin/figures/:id", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    if (!deps.figures?.get(id)) throw notFound("This figure");
    deps.figures.delete(id);
    return reply.code(204).send();
  });

  app.get("/admin/capacity", async () => ({
    figures: deps.figures?.capacity() ?? null,
    chat: { activeReplies: chat.activeCount() },
    routing: models.routing(),
  }));
}
