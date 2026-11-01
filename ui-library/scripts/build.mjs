// Bundles the renderer into one self-contained page in the app's assets:
// the WebView loads it with no network, so everything is inlined.
import * as esbuild from "esbuild";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const out = join(root, "..", "app", "src", "main", "assets", "openui");

const result = await esbuild.build({
  entryPoints: [join(root, "src", "app.tsx")],
  bundle: true,
  minify: true,
  format: "iife",
  target: "chrome100",
  jsx: "automatic",
  define: { "process.env.NODE_ENV": '"production"' },
  legalComments: "none",
  outdir: "out",
  write: false,
  logLevel: "warning",
});

const js = result.outputFiles.find((f) => f.path.endsWith(".js")).text.replace(/<\/script/gi, "<\\/script");
const css = result.outputFiles.find((f) => f.path.endsWith(".css")).text;
const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:">
<style>${css}</style>
</head>
<body>
<div id="root"></div>
<script>${js}</script>
</body>
</html>
`;
mkdirSync(out, { recursive: true });
writeFileSync(join(out, "index.html"), html);
console.log(`Wrote app/src/main/assets/openui/index.html (${(html.length / 1024).toFixed(0)} KB)`);
