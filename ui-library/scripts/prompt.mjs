// Writes the system prompt the server gives the chat model (dist/system-prompt.md).
import { writeFileSync } from "node:fs";
import { loadLibrary } from "./node-entry.mjs";

const { systemPrompt, LIBRARY_VERSION } = await loadLibrary();
const out = new URL("../dist/system-prompt.md", import.meta.url);
writeFileSync(out, systemPrompt());
console.log(`Wrote dist/system-prompt.md for library ${LIBRARY_VERSION}`);
