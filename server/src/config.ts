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
  /** The ui-library's generated prompt (npm run prompt) and the version it describes. */
  uiPromptFile: string;
  uiLibraryVersion: string;
  /** Figure workers running at once; size it to the hardware and provider rate limits. */
  figureWorkers: number;
  /** A checkout of the Anatomy skill; without it only the development runner works. */
  anatomyDir: string;
  openCodeBin: string;
  figureTimeoutMs: number;
  /** Development runner: time per stage. */
  devFigureStageMs: number;
  /** Chrome or Chromium for figure previews; empty skips previews. */
  chromeBin: string;
  /** Containers usually can't give Chrome its sandbox; the page still gets no network. */
  chromeNoSandbox: boolean;
  /** SMTP server for sign-in codes, e.g. smtps://user:pass@smtp.example.com:465; empty logs codes instead. */
  smtpUrl: string;
  mailFrom: string;
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
    uiPromptFile: env.VL_UI_PROMPT ?? join(process.cwd(), "..", "ui-library", "dist", "system-prompt.md"),
    uiLibraryVersion: env.VL_UI_LIBRARY_VERSION ?? "1.0.0",
    figureWorkers: Math.max(1, Number(env.VL_FIGURE_WORKERS ?? 2)),
    anatomyDir: env.VL_ANATOMY_DIR ?? "",
    openCodeBin: env.VL_OPENCODE_BIN ?? "opencode",
    figureTimeoutMs: Number(env.VL_FIGURE_TIMEOUT_MIN ?? 120) * 60_000,
    devFigureStageMs: Number(env.VL_DEV_FIGURE_STAGE_MS ?? 4000),
    chromeBin: env.VL_CHROME_BIN ?? env.CHROME_PATH ?? "",
    chromeNoSandbox: env.VL_CHROME_NO_SANDBOX === "1",
    smtpUrl: env.VL_SMTP_URL ?? "",
    mailFrom: env.VL_MAIL_FROM ?? "VisualLearn <no-reply@visuallearn.app>",
  };
}
