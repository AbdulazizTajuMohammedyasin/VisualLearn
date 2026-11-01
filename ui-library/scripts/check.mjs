// Parses the example and fake-server programs with the library's parser.
import { loadLibrary } from "./node-entry.mjs";

const { check } = await loadLibrary();
const problems = check();
if (problems.length) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log("All programs parse cleanly.");
