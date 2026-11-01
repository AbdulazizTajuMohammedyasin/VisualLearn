// Node entry for the build scripts: prompt generation and the parser check.
import { createParser } from "@openuidev/react-lang";
import { examples, fakeServerPrograms } from "./examples";
import { library, LIBRARY_VERSION } from "./library";

export { LIBRARY_VERSION };

/** The system prompt section the server gives the chat model. */
export function systemPrompt(): string {
  return library.prompt({
    preamble:
      "You are VisualLearn, a patient tutor. Answer in Markdown text. When practice or a step-by-step walk-through would help the learner, " +
      "add one interactive part written in OpenUI Lang using only the components below.",
    additionalRules: [
      "Use interactive parts for practice and processes, not for plain answers.",
      "Quizzes have 3 to 8 questions; each option is short; `answer` counts from 0.",
      "Only use a Figure with an id returned by the create_figure tool.",
    ],
    examples,
  });
}

/** Parses every example and fake-server program; returns one line per problem. */
export function check(): string[] {
  const parser = createParser(library.toJSONSchema(), library.root);
  const problems: string[] = [];
  [...examples, ...fakeServerPrograms].forEach((program, i) => {
    const result = parser.parse(program);
    if (!result.root) problems.push(`program ${i}: no root`);
    for (const e of result.meta.errors) problems.push(`program ${i}: ${JSON.stringify(e)}`);
    if (result.meta.unresolved.length) problems.push(`program ${i}: unresolved ${result.meta.unresolved.join(", ")}`);
  });
  return problems;
}
