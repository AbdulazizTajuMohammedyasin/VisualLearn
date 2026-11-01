import { createHash, randomBytes, randomUUID } from "node:crypto";

/** Opaque ids with a readable prefix, e.g. `usr_3f9c…`. */
export const newId = (prefix: string) => `${prefix}_${randomUUID().replace(/-/g, "").slice(0, 20)}`;

export const randomToken = (bytes = 32) => randomBytes(bytes).toString("base64url");

export const sha256 = (data: string | Buffer) => createHash("sha256").update(data).digest("hex");

export const iso = (ms: number | null | undefined) => (ms == null ? null : new Date(ms).toISOString());

/** Compares dotted versions: negative when a < b. */
export function compareVersions(a: string, b: string): number {
  const pa = a.split(".").map((n) => Number.parseInt(n, 10) || 0);
  const pb = b.split(".").map((n) => Number.parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}
