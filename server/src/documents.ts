import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { FastifyInstance } from "fastify";
import { extractText, getDocumentProxy } from "unpdf";
import type { Deps } from "./app.ts";
import { badRequest, notFound } from "./errors.ts";
import { iso, newId } from "./util.ts";

/** A PDF a learner attached to a chat, kept as the original file plus its text. */
export interface DocumentRow {
  id: string;
  user_id: string;
  conversation_id: string;
  name: string;
  bytes: number;
  pages: number;
  text: string;
  created_at: number;
}

export const MAX_PDF_BYTES = 25 * 1024 * 1024;
/** Text kept per document; about 30k tokens, past which a model is better given the file itself. */
const MAX_TEXT_CHARS = 120_000;

export const documentJson = (d: DocumentRow) => ({
  id: d.id,
  name: d.name,
  pages: d.pages,
  bytes: d.bytes,
  hasText: d.text.trim().length > 0,
  createdAt: iso(d.created_at),
});

/** Stored PDFs: rows in the database, files under [dir] named by document id. */
export class Documents {
  private readonly deps: Deps;
  private readonly dir: string;

  constructor(deps: Deps, dir: string) {
    this.deps = deps;
    this.dir = dir;
    mkdirSync(dir, { recursive: true });
  }

  async add(userId: string, conversationId: string, name: string, pdf: Buffer): Promise<DocumentRow> {
    if (pdf.length === 0) throw badRequest("That file is empty.");
    if (pdf.length > MAX_PDF_BYTES) throw badRequest("That PDF is too big. The limit is 25 MB.");
    if (pdf.subarray(0, 5).toString("latin1") !== "%PDF-") throw badRequest("That file isn't a PDF.");
    let pages: number;
    let text: string;
    try {
      const doc = await getDocumentProxy(new Uint8Array(pdf));
      const result = await extractText(doc, { mergePages: false });
      pages = result.totalPages;
      // Page markers let the model cite where things are.
      text = (result.text as string[]).map((t, i) => `--- page ${i + 1} ---\n${t.trim()}`).join("\n\n").slice(0, MAX_TEXT_CHARS);
      await doc.cleanup();
    } catch {
      throw badRequest("That PDF couldn't be opened. It may be damaged or password-protected.");
    }
    const id = newId("doc");
    const file = this.file(id);
    writeFileSync(`${file}.part`, pdf);
    renameSync(`${file}.part`, file);
    const clean = name.replace(/[\r\n\t]/g, " ").trim().slice(0, 200) || "Document.pdf";
    this.deps.db
      .prepare("INSERT INTO documents (id, user_id, conversation_id, name, bytes, pages, text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .run(id, userId, conversationId, clean, pdf.length, pages, text, this.deps.now());
    return this.get(id)!;
  }

  get(id: string): DocumentRow | undefined {
    return this.deps.db.prepare("SELECT * FROM documents WHERE id = ?").get(id) as DocumentRow | undefined;
  }

  /** The original PDF, or null if it is gone. */
  read(id: string): Buffer | null {
    const file = this.file(id);
    return existsSync(file) ? readFileSync(file) : null;
  }

  /** Removes files whose rows were deleted (with their chat or account). */
  sweep() {
    const kept = new Set((this.deps.db.prepare("SELECT id FROM documents").all() as { id: string }[]).map((r) => r.id));
    for (const name of readdirSync(this.dir)) {
      const id = name.replace(/\.pdf(\.part)?$/, "");
      if (!kept.has(id)) rmSync(join(this.dir, name), { force: true });
    }
  }

  private file(id: string) {
    return join(this.dir, `${id}.pdf`);
  }
}

export function documentRoutes(app: FastifyInstance, deps: Deps, documents: Documents) {
  app.addContentTypeParser("application/pdf", { parseAs: "buffer", bodyLimit: MAX_PDF_BYTES + 1024 }, (_request, body, done) => done(null, body));

  // The body is the PDF itself; its file name comes in the query.
  app.post("/conversations/:id/documents", async (request, reply) => {
    const id = (request.params as { id: string }).id;
    const c = deps.db.prepare("SELECT user_id FROM conversations WHERE id = ?").get(id) as { user_id: string } | undefined;
    if (!c || c.user_id !== request.user.id) throw notFound("This chat");
    if (!Buffer.isBuffer(request.body)) throw badRequest("Send the PDF as the request body, with Content-Type: application/pdf.");
    const name = (request.query as { name?: string }).name ?? "Document.pdf";
    const doc = await documents.add(request.user.id, id, name, request.body);
    return reply.code(201).send(documentJson(doc));
  });
}
