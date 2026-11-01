import * as esbuild from "esbuild";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

/** Bundles src/node.ts for Node and imports it. */
export async function loadLibrary() {
  mkdirSync(join(root, "dist"), { recursive: true });
  const outfile = join(root, "dist", "node.mjs");
  await esbuild.build({
    entryPoints: [join(root, "src", "node.ts")],
    bundle: true,
    platform: "node",
    format: "esm",
    jsx: "automatic",
    outfile,
    logLevel: "warning",
  });
  return import(`${pathToFileURL(outfile).href}?t=${Date.now()}`);
}
