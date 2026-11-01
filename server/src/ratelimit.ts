import { rateLimited } from "./errors.ts";

/**
 * Abuse protection (section 6): at most [limit] requests per key per minute. This is not
 * a usage cap; normal use never comes close. Counts are per server process.
 */
export class RateLimiter {
  private readonly limit: number;
  private readonly now: () => number;
  private readonly windows = new Map<string, { start: number; count: number }>();

  constructor(limit: number, now: () => number) {
    this.limit = limit;
    this.now = now;
  }

  /** Counts a request for [key]; throws 429 with Retry-After when over the limit. */
  hit(key: string) {
    const t = this.now();
    let w = this.windows.get(key);
    if (!w || t - w.start >= 60_000) {
      w = { start: t, count: 0 };
      this.windows.set(key, w);
      if (this.windows.size > 50_000) this.sweep(t);
    }
    w.count++;
    if (w.count > this.limit) throw rateLimited(Math.max(1, Math.ceil((w.start + 60_000 - t) / 1000)));
  }

  private sweep(t: number) {
    for (const [key, w] of this.windows) if (t - w.start >= 60_000) this.windows.delete(key);
  }
}

export interface RateLimits {
  /** Sign-in endpoints, per client IP. */
  auth: RateLimiter;
  /** Any signed-in request, per user. */
  requests: RateLimiter;
  /** New chat messages and figure jobs, per user: the expensive ones. */
  work: RateLimiter;
}

export function rateLimits(now: () => number, env: NodeJS.ProcessEnv = process.env): RateLimits {
  return {
    auth: new RateLimiter(Number(env.VL_RATE_AUTH_PER_MIN ?? 30), now),
    requests: new RateLimiter(Number(env.VL_RATE_REQUESTS_PER_MIN ?? 600), now),
    work: new RateLimiter(Number(env.VL_RATE_WORK_PER_MIN ?? 30), now),
  };
}
