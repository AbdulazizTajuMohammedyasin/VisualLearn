import { join } from "node:path";

/** Server settings, from the environment. */
export interface Config {
  port: number;
  host: string;
  /** Where the database and stored figure pages live. */
  dataDir: string;
  /** Public base URL of this server, used in signed page links (e.g. https://api.example.com). */
  publicUrl: string;
  /** Secret for signing page URLs; must be set in production. */
  signingSecret: string;
  /** OAuth web client ID the app's Google ID tokens are issued for; empty disables Google sign-in. */
  googleClientId: string;
  /** Emails that become admins when they sign in. */
  adminEmails: string[];
  /** Oldest UI library version the server still emits programs for (section 8). */
  minUiLibraryVersion: string;
  /** Development only: accept the email code 123456 and log codes instead of sending them. */
  devAuth: boolean;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const port = Number(env.PORT ?? 8080);
  const devAuth = env.VL_DEV_AUTH === "1";
  const signingSecret = env.VL_SIGNING_SECRET ?? (devAuth ? "dev-only-secret" : "");
  if (!signingSecret) throw new Error("VL_SIGNING_SECRET must be set (or VL_DEV_AUTH=1 for local development)");
  return {
    port,
    host: env.HOST ?? "0.0.0.0",
    dataDir: env.VL_DATA_DIR ?? join(process.cwd(), "data"),
    publicUrl: (env.VL_PUBLIC_URL ?? `http://localhost:${port}`).replace(/\/$/, ""),
    signingSecret,
    googleClientId: env.VL_GOOGLE_CLIENT_ID ?? "",
    adminEmails: (env.VL_ADMIN_EMAILS ?? "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean),
    minUiLibraryVersion: env.VL_MIN_UI_LIBRARY ?? "1.0.0",
    devAuth,
  };
}
