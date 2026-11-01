import assert from "node:assert/strict";
import { test } from "node:test";
import { FenceSplitter, type FenceEvent } from "../src/fence.ts";

/** Feeds text in chunks of [size] and joins the result into parts. */
function split(text: string, size: number) {
  const s = new FenceSplitter();
  const events: FenceEvent[] = [];
  for (let i = 0; i < text.length; i += size) events.push(...s.push(text.slice(i, i + size)));
  events.push(...s.end());
  const parts: { type: string; content: string }[] = [];
  for (const e of events) {
    if (e.type === "text-start") parts.push({ type: "text", content: "" });
    else if (e.type === "ui-start") parts.push({ type: "ui", content: "" });
    else parts[parts.length - 1].content += e.type === "text" ? e.text : e.program;
  }
  return parts;
}

const reply = "Here's a quiz.\n\n```openui\nroot = Quiz(\"Z\", [q1])\nq1 = MultipleChoice(\"Q\", [\"a\", \"b\"], 0)\n```\n\nGood luck!";

for (const size of [1, 2, 3, 7, 1000]) {
  test(`splits text and ui parts with chunks of ${size}`, () => {
    assert.deepEqual(split(reply, size), [
      { type: "text", content: "Here's a quiz.\n\n" },
      { type: "ui", content: "root = Quiz(\"Z\", [q1])\nq1 = MultipleChoice(\"Q\", [\"a\", \"b\"], 0)" },
      { type: "text", content: "Good luck!" },
    ]);
  });
}

test("plain backticks stay text", () => {
  assert.deepEqual(split("Use `x` and ```js\ncode\n```", 2), [{ type: "text", content: "Use `x` and ```js\ncode\n```" }]);
});

test("an unclosed program keeps what arrived", () => {
  assert.deepEqual(split("Try:\n```openui\nroot = Lesson([a])", 4), [
    { type: "text", content: "Try:\n" },
    { type: "ui", content: "root = Lesson([a])" },
  ]);
});
