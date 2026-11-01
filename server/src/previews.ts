import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

/** Draws a still image of a finished figure page, shown in figure cards and lists. */
export interface Previewer {
  render(pageFile: string, outPng: string): Promise<void>;
}

/** A narrow window, so Anatomy's figure plate fills most of the shot. */
export const PREVIEW_WIDTH = 680;
export const PREVIEW_HEIGHT = 520;

/**
 * Headless Chrome screenshot of the page. The page is model-written, so the
 * browser gets no network (every host resolves to nothing) and a throwaway profile.
 */
export class ChromePreviewer implements Previewer {
  private readonly bin: string;
  private readonly noSandbox: boolean;
  private readonly timeoutMs: number;

  constructor(options: { bin: string; noSandbox?: boolean; timeoutMs?: number }) {
    this.bin = options.bin;
    this.noSandbox = options.noSandbox ?? false;
    this.timeoutMs = options.timeoutMs ?? 60_000;
  }

  async render(pageFile: string, outPng: string) {
    const profile = mkdtempSync(join(tmpdir(), "vl-preview-"));
    const args = [
      "--headless=new",
      "--hide-scrollbars",
      "--mute-audio",
      "--no-first-run",
      "--disable-extensions",
      "--disable-background-networking",
      '--host-resolver-rules=MAP * ~NOTFOUND',
      `--user-data-dir=${profile}`,
      `--window-size=${PREVIEW_WIDTH},${PREVIEW_HEIGHT}`,
      // Sharp on phone screens.
      "--force-device-scale-factor=1.5",
      // Let entry animations settle before the shot.
      "--virtual-time-budget=4000",
      `--screenshot=${outPng}`,
      ...(this.noSandbox ? ["--no-sandbox"] : []),
      pathToFileURL(pageFile).href,
    ];
    try {
      await new Promise<void>((resolve, reject) => {
        const child = spawn(this.bin, args, { stdio: "ignore", windowsHide: true });
        const timer = setTimeout(() => child.kill("SIGKILL"), this.timeoutMs);
        child.on("error", (e) => (clearTimeout(timer), reject(e)));
        child.on("exit", (code, signal) => {
          clearTimeout(timer);
          code === 0 ? resolve() : reject(new Error(`chrome exited with ${signal ?? code}`));
        });
      });
    } finally {
      rmSync(profile, { recursive: true, force: true });
    }
  }
}
