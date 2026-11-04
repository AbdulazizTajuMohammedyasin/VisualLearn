// Checks whether models behind an OpenAI-compatible endpoint (vLLM, SGLang,
// Ollama, LM Studio, a university gateway) can run VisualLearn's lessons.
// LearnHarness needs tool calling on every turn, nested structured tool
// arguments for the knowledge graph, a long context, and images for the
// vision role; chat and figures need streaming. Each check is one request.
//
//   node scripts/check-models.ts --base https://gpu.uni.example/v1 --key "$KEY" big-model vision-model
//   node scripts/check-models.ts --base http://localhost:11434/v1 --context 32000 qwen3:32b
//
// Options: --base URL (required), --key KEY (or VL_CHECK_KEY), --context TOKENS
// (the long-context check's size, default 24000; 0 skips it), --no-vision,
// --timeout SECONDS (per request, default 180).
import { deflateSync } from "node:zlib";

interface Options { base: string; key: string; context: number; vision: boolean; timeoutMs: number; models: string[] }

function options(argv: string[]): Options {
  const o: Options = { base: "", key: process.env.VL_CHECK_KEY ?? "", context: 24000, vision: true, timeoutMs: 180_000, models: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--base") o.base = argv[++i] ?? "";
    else if (a === "--key") o.key = argv[++i] ?? "";
    else if (a === "--context") o.context = Number(argv[++i]);
    else if (a === "--no-vision") o.vision = false;
    else if (a === "--timeout") o.timeoutMs = Number(argv[++i]) * 1000;
    else if (a.startsWith("--")) throw new Error(`Unknown option ${a}`);
    else o.models.push(a);
  }
  if (!o.base || o.models.length === 0) throw new Error("Usage: node scripts/check-models.ts --base URL [--key KEY] MODEL...");
  o.base = o.base.replace(/\/+$/, "");
  return o;
}

type Message = { role: string; content: unknown; tool_calls?: unknown };
interface Reply { text: string; calls: { name: string; args: string }[]; firstTokenMs: number; totalMs: number; outputChars: number; finish: string }

/** One streamed chat completion, assembled. */
async function complete(o: Options, model: string, body: Record<string, unknown>): Promise<Reply> {
  const started = performance.now();
  const response = await fetch(`${o.base}/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(o.key ? { authorization: `Bearer ${o.key}` } : {}) },
    body: JSON.stringify({ model, stream: true, ...body }),
    signal: AbortSignal.timeout(o.timeoutMs),
  });
  if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
  let text = "";
  let firstTokenMs = 0;
  let finish = "";
  const calls: { name: string; args: string }[] = [];
  let buffer = "";
  const decoder = new TextDecoder();
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk as Uint8Array, { stream: true });
    let end: number;
    while ((end = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, end).trim();
      buffer = buffer.slice(end + 1);
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (data === "[DONE]") continue;
      const event = JSON.parse(data);
      if (event.error) throw new Error(JSON.stringify(event.error).slice(0, 300));
      const choice = event.choices?.[0];
      if (!choice) continue;
      const delta = choice.delta ?? {};
      if ((delta.content || delta.tool_calls || delta.reasoning_content) && !firstTokenMs) firstTokenMs = performance.now() - started;
      if (typeof delta.content === "string") text += delta.content;
      for (const call of delta.tool_calls ?? []) {
        const at = call.index ?? calls.length;
        calls[at] ??= { name: "", args: "" };
        if (call.function?.name) calls[at].name += call.function.name;
        if (call.function?.arguments) calls[at].args += call.function.arguments;
      }
      if (choice.finish_reason) finish = choice.finish_reason;
    }
  }
  return { text, calls: calls.filter(Boolean), firstTokenMs, totalMs: performance.now() - started, outputChars: text.length + calls.reduce((n, c) => n + c.args.length, 0), finish };
}

const tool = (name: string, description: string, parameters: Record<string, unknown>) => ({ type: "function", function: { name, description, parameters } });
const object = (properties: Record<string, unknown>, required = Object.keys(properties)) => ({ type: "object", properties, required, additionalProperties: false });
const str = (description: string) => ({ type: "string", description });

/** Tools shaped like a teaching turn's: the model must pick the right one among many. */
const LESSON_TOOLS = [
  tool("quiz", "Ask a multiple-choice question to check understanding.", object({ question: str("The question."), options: { type: "array", items: object({ label: str("Shown text."), value: str("Stable value.") }) }, correctAnswer: str("The right option's value.") })),
  tool("explain", "Ask the learner to explain something in their own words.", object({ question: str("What to explain."), correctAnswer: str("A model answer.") })),
  tool("research", "Look something up on the web in the background.", object({ question: str("What to find out.") })),
  tool("figure", "Have a 3D figure built in the background.", object({ title: str("Short title."), brief: str("What it shows and how it moves.") })),
  tool("demo", "Have an interactive HTML demo built in the background.", object({ title: str("Short title."), brief: str("What it lets the learner do.") })),
  tool("visual", "Have a static SVG diagram drawn.", object({ brief: str("What the diagram shows.") })),
  tool("pdf", "Read a PDF in the learner's files.", object({ path: str("Path like ~/notes.pdf."), question: str("What to look for.") })),
  tool("plan_update", "Revise the lesson plan.", object({ nodes: { type: "array", items: object({ id: str("Id."), title: str("Title.") }) } })),
  tool("recall", "Ask the learner to recall a fact from memory.", object({ question: str("The question."), correctAnswer: str("The answer.") })),
  tool("send_to_user", "Show the learner a file.", object({ path: str("The file.") })),
  tool("ask_user_question", "Ask the learner a plain question with options.", object({ question: str("The question."), options: { type: "array", items: object({ label: str("Shown text."), value: str("Stable value.") }) } })),
];

/** The knowledge graph extractor's submit tool: nested arrays of objects with cross-references. */
const SUBMIT_FINDINGS = tool("submit_findings", "Submit the core ideas of the notes and the reasoning chains between them.", object({
  propositions: { type: "array", items: object({ id: str('Local id: "p1", "p2", …'), name: str("A very short label."), statement: str("One true, self-contained statement.") }) },
  chains: { type: "array", items: object({ premises: { type: "array", minItems: 1, items: str("Proposition ids.") }, conclusion: str("Proposition id.") }) },
}));

const NOTES = "Only the power stroke pushes the crankshaft; intake, compression and exhaust take energy. A heavy flywheel stores energy from the power stroke and carries the engine through the other three strokes, so the crankshaft keeps turning smoothly. The camshaft turns at half crankshaft speed.";

/** An 8×8 PNG of one colour, made here so the check needs no files. */
function solidPng(r: number, g: number, b: number): string {
  const size = 8;
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf: Buffer) => { let c = 0xffffffff; for (const byte of buf) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type: string, data: Buffer) => {
    const head = Buffer.alloc(4); head.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type), data]);
    const tail = Buffer.alloc(4); tail.writeUInt32BE(crc(body));
    return Buffer.concat([head, body, tail]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 2;
  const rows = Buffer.concat(Array.from({ length: size }, () => Buffer.from([0, ...Array.from({ length: size }, () => [r, g, b]).flat()])));
  const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(rows)), chunk("IEND", Buffer.alloc(0))]);
  return `data:image/png;base64,${png.toString("base64")}`;
}

interface Check { name: string; required: boolean; run: (o: Options, model: string) => Promise<string> }

const user = (content: unknown): Message => ({ role: "user", content });

const CHECKS: Check[] = [
  {
    name: "streaming",
    required: true,
    run: async (o, model) => {
      const r = await complete(o, model, { messages: [user("In three sentences, why does a four-stroke engine need a flywheel?")], max_tokens: 400 });
      if (r.text.trim().length < 40) throw new Error(`reply too short: ${JSON.stringify(r.text.slice(0, 80))}`);
      // Over the whole reply: some servers send text in a few large chunks.
      const rate = r.outputChars / 4 / (r.totalMs / 1000);
      return `first token ${(r.firstTokenMs / 1000).toFixed(1)}s, whole reply ${(r.totalMs / 1000).toFixed(1)}s, ~${rate.toFixed(0)} tokens/s`;
    },
  },
  {
    name: "tool choice among 11 tools",
    required: true,
    run: async (o, model) => {
      const r = await complete(o, model, {
        messages: [
          { role: "system", content: "You are a teacher. Use your tools; don't answer in plain text when a tool fits." },
          user("I just learned that only one stroke in four pushes the piston. Check that I understood it with a multiple-choice question."),
        ],
        tools: LESSON_TOOLS,
        max_tokens: 1500,
      });
      const quiz = r.calls.find((c) => c.name === "quiz");
      if (!quiz) throw new Error(r.calls.length ? `called ${r.calls.map((c) => c.name).join(", ")} instead of quiz` : `no tool call (said: ${JSON.stringify(r.text.slice(0, 80))})`);
      const args = JSON.parse(quiz.args);
      if (!args.question || !Array.isArray(args.options) || args.options.length < 2) throw new Error(`quiz arguments incomplete: ${quiz.args.slice(0, 160)}`);
      return `quiz with ${args.options.length} options`;
    },
  },
  {
    name: "forced structured tool call",
    required: true,
    run: async (o, model) => {
      const r = await complete(o, model, {
        messages: [{ role: "system", content: "Extract the core ideas from the notes and submit them." }, user(NOTES)],
        tools: [SUBMIT_FINDINGS],
        tool_choice: { type: "function", function: { name: "submit_findings" } },
        max_tokens: 3000,
      });
      const call = r.calls.find((c) => c.name === "submit_findings");
      if (!call) throw new Error("submit_findings wasn't called");
      const args = JSON.parse(call.args);
      const ids = new Set((args.propositions ?? []).map((p: { id: string }) => p.id));
      if (ids.size < 2) throw new Error(`only ${ids.size} propositions`);
      const dangling = (args.chains ?? []).flatMap((c: { premises: string[]; conclusion: string }) => [...c.premises, c.conclusion]).filter((id: string) => !ids.has(id));
      if (dangling.length) throw new Error(`chains refer to unknown ids: ${dangling.join(", ")}`);
      return `${ids.size} propositions, ${(args.chains ?? []).length} chains`;
    },
  },
  {
    name: "long context",
    required: true,
    run: async (o, model) => {
      if (!o.context) return "skipped";
      // Filler sized to the requested tokens (about 4 characters each), with one fact in the middle.
      const sentence = "The museum's east wing holds pottery from several centuries, arranged by region and kiln. ";
      const filler = sentence.repeat(Math.ceil((o.context * 4) / sentence.length / 2));
      const text = `${filler}The access code for the storeroom is 4417-KESTREL. ${filler}`;
      const r = await complete(o, model, { messages: [user(`${text}\n\nWhat is the access code for the storeroom? Reply with the code only.`)], max_tokens: 200 });
      if (!r.text.includes("4417-KESTREL")) throw new Error(`didn't find the code in ~${o.context} tokens (said: ${JSON.stringify(r.text.slice(0, 80))})`);
      return `found a fact in ~${o.context} tokens in ${(r.totalMs / 1000).toFixed(1)}s`;
    },
  },
  {
    name: "image input",
    required: false,
    run: async (o, model) => {
      if (!o.vision) return "skipped";
      const r = await complete(o, model, {
        messages: [user([{ type: "text", text: "What single colour fills this image? Answer with one word." }, { type: "image_url", image_url: { url: solidPng(220, 30, 30) } }])],
        max_tokens: 200,
      });
      if (!/red/i.test(r.text)) throw new Error(`said ${JSON.stringify(r.text.slice(0, 60))}`);
      return "read the image";
    },
  },
];

const o = options(process.argv.slice(2));
let failedRequired = false;
for (const model of o.models) {
  console.log(`\n${model}`);
  for (const check of CHECKS) {
    try {
      console.log(`  ok    ${check.name}: ${await check.run(o, model)}`);
    } catch (e) {
      if (check.required) failedRequired = true;
      // fetch's own message is just "fetch failed"; the reason is its cause.
      const cause = (e as { cause?: { code?: string; message?: string } }).cause;
      const why = cause ? `${(e as Error).message} (${cause.code ?? cause.message})` : (e as Error).message;
      console.log(`  ${check.required ? "FAIL" : "no  "}  ${check.name}: ${why}`);
    }
  }
}
console.log(failedRequired
  ? "\nAt least one model can't run lessons as they are: every lesson turn uses tools, and the graph needs the structured call."
  : "\nEvery model passed the checks lessons need. Image input is needed only for the vision role.");
process.exitCode = failedRequired ? 1 : 0;
