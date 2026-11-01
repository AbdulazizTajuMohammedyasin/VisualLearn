/** An error with the body the app expects (docs/server-api.md, section 2). `message` is shown to users. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryable: boolean;
  readonly headers: Record<string, string>;

  constructor(status: number, code: string, message: string, options: { retryable?: boolean; headers?: Record<string, string> } = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryable = options.retryable ?? status >= 500;
    this.headers = options.headers ?? {};
  }

  body() {
    return { error: { code: this.code, message: this.message, retryable: this.retryable } };
  }
}

export const badRequest = (message: string) => new ApiError(400, "invalid_request", message);
export const unauthenticated = () => new ApiError(401, "unauthenticated", "Please sign in again.");
export const forbidden = () => new ApiError(403, "forbidden", "You don't have access to this.");
export const notFound = (what = "This item") => new ApiError(404, "not_found", `${what} no longer exists.`);
export const conflict = (message: string) => new ApiError(409, "conflict", message);
export const rateLimited = (seconds: number, message = "You're going a bit fast. Try again in a few seconds.") =>
  new ApiError(429, "rate_limited", message, { retryable: true, headers: { "Retry-After": String(seconds) } });
