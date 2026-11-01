import { createLibrary, defineComponent, type ElementNode } from "@openuidev/react-lang";
import { Fragment, type ReactNode } from "react";
import { z } from "zod/v4";
import { askAssistant, post, useSaved, useSavedStore } from "./host";

/** The library version the app reports in X-UI-Library-Version; bump on any change to names or props. */
export const LIBRARY_VERSION = "1.0.0";

// --- helpers ---

/** `**bold**` and `code` inside plain strings; the model writes short text, not full Markdown. */
function rich(text: string | undefined): ReactNode {
  if (!text) return null;
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((piece, i) => {
    if (piece.startsWith("**") && piece.endsWith("**") && piece.length > 4) return <strong key={i}>{piece.slice(2, -2)}</strong>;
    if (piece.startsWith("`") && piece.endsWith("`") && piece.length > 2) return <code key={i}>{piece.slice(1, -1)}</code>;
    return <Fragment key={i}>{piece}</Fragment>;
  });
}

/** Child components arrive as parsed nodes; while streaming some may still be missing or partial. */
function elements(value: unknown, typeName: string): ElementNode[] {
  if (!Array.isArray(value)) return [];
  return value.filter((v): v is ElementNode => !!v && typeof v === "object" && (v as ElementNode).type === "element" && (v as ElementNode).typeName === typeName);
}

function keyOf(node: ElementNode, index: number) {
  return node.statementId ?? `#${index}`;
}

const LETTERS = "ABCDEFGH";

// --- MultipleChoice ---

interface QuestionProps {
  question: string;
  options: string[];
  answer: number;
  explanation?: string;
}

function QuestionView({ id, q, number }: { id: string; q: QuestionProps; number?: number }) {
  const [chosen, setChosen] = useSaved<number | null>(`mc:${id}`, null);
  const answered = chosen !== null;
  return (
    <div className="question">
      <p className="question-text">
        {number !== undefined && <span className="question-number">{number}</span>}
        {rich(q.question)}
      </p>
      <div className="options" role="radiogroup">
        {(q.options ?? []).map((option, i) => {
          const state = !answered ? "" : i === q.answer ? "correct" : i === chosen ? "wrong" : "dim";
          return (
            <button
              key={i}
              className={`option ${state}`}
              role="radio"
              aria-checked={chosen === i}
              disabled={answered}
              onClick={() => setChosen(i)}
            >
              <span className="letter">{LETTERS[i]}</span>
              <span>{rich(option)}</span>
            </button>
          );
        })}
      </div>
      {answered && (
        <div className={`feedback ${chosen === q.answer ? "correct" : "wrong"}`} role="status">
          <strong>{chosen === q.answer ? "Right." : `Not quite: the answer is ${LETTERS[q.answer]}.`}</strong>{" "}
          {rich(q.explanation)}
        </div>
      )}
    </div>
  );
}

const MultipleChoiceProps = z.object({
  question: z.string().describe("The question, one sentence"),
  options: z.array(z.string()).describe("2 to 5 short options"),
  answer: z.number().describe("Index of the correct option, counting from 0"),
  explanation: z.string().optional().describe("Why the answer is right, shown after answering"),
});

export const MultipleChoice = defineComponent({
  name: "MultipleChoice",
  description: "One question with options. Shows right or wrong at once, then the explanation.",
  props: MultipleChoiceProps,
  component: ({ props, statementId }) => (
    <section className="block">
      <QuestionView id={statementId ?? props.question} q={props} />
    </section>
  ),
});

// --- Quiz ---

export const Quiz = defineComponent({
  name: "Quiz",
  description: "Several MultipleChoice questions with a score. When finished the learner can ask for their mistakes to be explained.",
  props: z.object({
    title: z.string().describe("Short topic name"),
    questions: z.array(MultipleChoice.ref).describe("3 to 8 questions"),
  }),
  component: function QuizView({ props, statementId }) {
    const quizId = statementId ?? props.title;
    const nodes = elements(props.questions, "MultipleChoice");
    const questions = nodes.map((n, i) => ({ id: `${quizId}/${keyOf(n, i)}`, q: n.props as unknown as QuestionProps }));
    const store = useSavedStore();
    const answers = questions.map(({ id }) => store.get<number | null>(`mc:${id}`, null));
    const done = questions.length > 0 && answers.every((a) => a !== null);
    const score = questions.filter(({ q }, i) => answers[i] === q.answer).length;
    const [asked, setAsked] = useSaved<boolean>(`quiz:${quizId}:asked`, false);
    const mistakes = questions
      .map(({ q }, i) => ({ q, chosen: answers[i] }))
      .filter(({ q, chosen }) => chosen !== null && chosen !== q.answer);

    return (
      <section className="block quiz">
        <header className="block-header">
          <span className="eyebrow">Quiz</span>
          <h2>{rich(props.title)}</h2>
          <span className="count">
            {answers.filter((a) => a !== null).length} / {questions.length}
          </span>
        </header>
        {questions.map(({ id, q }, i) => (
          <QuestionView key={id} id={id} q={q} number={i + 1} />
        ))}
        {done && (
          <footer className="result">
            <p className="score">
              {score} of {questions.length} right
            </p>
            {mistakes.length > 0 && (
              <button
                className="primary"
                disabled={asked}
                onClick={() => {
                  setAsked(true);
                  askAssistant("review_mistakes", "Explain the questions I got wrong.", {
                    quiz: props.title,
                    score,
                    total: questions.length,
                    mistakes: mistakes.map(({ q, chosen }) => ({
                      question: q.question,
                      chosen: q.options[chosen as number],
                      correct: q.options[q.answer],
                    })),
                  });
                }}
              >
                {asked ? "Asked" : "Explain my mistakes"}
              </button>
            )}
          </footer>
        )}
      </section>
    );
  },
});

// --- Flashcards ---

export const Card = defineComponent({
  name: "Card",
  description: "One flashcard for Flashcards.",
  props: z.object({
    front: z.string().describe("Term or question, a few words"),
    back: z.string().describe("Answer or definition, one or two sentences"),
  }),
  component: ({ props }) => (
    <div className="flashcard static">
      <p className="front">{rich(props.front)}</p>
      <p className="back">{rich(props.back)}</p>
    </div>
  ),
});

interface DeckState {
  round: number[]; // indexes in this round
  position: number;
  missed: number[];
  flipped: boolean;
}

export const Flashcards = defineComponent({
  name: "Flashcards",
  description: "A deck to practise recall: tap a card to flip it, then mark it known or again. Missed cards come back in another round.",
  props: z.object({
    title: z.string().describe("Short topic name"),
    cards: z.array(Card.ref).describe("4 to 12 cards"),
  }),
  component: function FlashcardsView({ props, statementId }) {
    const cards = elements(props.cards, "Card").map((n) => n.props as { front: string; back: string });
    const all = cards.map((_, i) => i);
    const [deck, setDeck] = useSaved<DeckState | null>(`deck:${statementId ?? props.title}`, null);
    const state: DeckState = deck ?? { round: all, position: 0, missed: [], flipped: false };
    const round = deck ? state.round : all; // while streaming, the first round grows with the deck
    const finished = state.position >= round.length && round.length > 0;
    const current = cards[round[state.position]];

    const mark = (known: boolean) =>
      setDeck({
        ...state,
        round,
        position: state.position + 1,
        missed: known ? state.missed : [...state.missed, round[state.position]],
        flipped: false,
      });

    return (
      <section className="block deck">
        <header className="block-header">
          <span className="eyebrow">Flashcards</span>
          <h2>{rich(props.title)}</h2>
          <span className="count">{finished ? "Done" : `${Math.min(state.position + 1, round.length)} / ${round.length}`}</span>
        </header>
        {finished ? (
          <div className="result">
            <p className="score">
              You knew {round.length - state.missed.length} of {round.length}
            </p>
            <div className="row">
              {state.missed.length > 0 && (
                <button className="primary" onClick={() => setDeck({ round: state.missed, position: 0, missed: [], flipped: false })}>
                  Practise the {state.missed.length} I missed
                </button>
              )}
              <button onClick={() => setDeck({ round: all, position: 0, missed: [], flipped: false })}>Start over</button>
            </div>
          </div>
        ) : current ? (
          <>
            <button
              className={`flashcard ${state.flipped ? "flipped" : ""}`}
              aria-label={state.flipped ? "Card back" : "Card front, tap to flip"}
              onClick={() => setDeck({ ...state, round, flipped: !state.flipped })}
            >
              <span className="face">{rich(state.flipped ? current.back : current.front)}</span>
              <span className="hint">{state.flipped ? "Answer" : "Tap to flip"}</span>
            </button>
            <div className="row">
              <button disabled={!state.flipped} onClick={() => mark(false)}>
                Again
              </button>
              <button className="primary" disabled={!state.flipped} onClick={() => mark(true)}>
                Knew it
              </button>
            </div>
          </>
        ) : null}
      </section>
    );
  },
});

// --- StepExplainer ---

export const Step = defineComponent({
  name: "Step",
  description: "One step for StepExplainer.",
  props: z.object({
    title: z.string().describe("What happens in this step, a few words"),
    body: z.string().describe("Two or three sentences"),
  }),
  component: ({ props }) => (
    <div className="step static">
      <h3>{rich(props.title)}</h3>
      <p>{rich(props.body)}</p>
    </div>
  ),
});

export const StepExplainer = defineComponent({
  name: "StepExplainer",
  description: "A process explained one step at a time, with back and next.",
  props: z.object({
    title: z.string().describe("The process being explained"),
    steps: z.array(Step.ref).describe("3 to 8 steps, in order"),
  }),
  component: function StepExplainerView({ props, statementId }) {
    const steps = elements(props.steps, "Step").map((n) => n.props as { title: string; body: string });
    const [index, setIndex] = useSaved<number>(`steps:${statementId ?? props.title}`, 0);
    const current = steps[Math.min(index, steps.length - 1)];
    return (
      <section className="block steps">
        <header className="block-header">
          <span className="eyebrow">Step by step</span>
          <h2>{rich(props.title)}</h2>
          <span className="count">
            {Math.min(index + 1, steps.length)} / {steps.length}
          </span>
        </header>
        <ol className="dots" aria-hidden="true">
          {steps.map((_, i) => (
            <li key={i} className={i === index ? "on" : i < index ? "past" : ""} />
          ))}
        </ol>
        {current && (
          <div className="step" aria-live="polite">
            <h3>{rich(current.title)}</h3>
            <p>{rich(current.body)}</p>
          </div>
        )}
        <div className="row">
          <button disabled={index === 0} onClick={() => setIndex(index - 1)}>
            Back
          </button>
          <button className="primary" disabled={index >= steps.length - 1} onClick={() => setIndex(index + 1)}>
            Next
          </button>
        </div>
      </section>
    );
  },
});

// --- Figure, Callout, Text ---

export const Figure = defineComponent({
  name: "Figure",
  description: "Points to an interactive figure job by id (from the create_figure tool). The app shows the figure's card.",
  props: z.object({
    figureId: z.string().describe("Id returned by create_figure, e.g. fig_123"),
    caption: z.string().optional().describe("What to look for in the figure"),
  }),
  component: ({ props }) => (
    <section className="block figure">
      <span className="eyebrow">Figure</span>
      {props.caption && <p>{rich(props.caption)}</p>}
      <button className="primary" onClick={() => post({ type: "openFigure", figureId: props.figureId })}>
        Open figure
      </button>
    </section>
  ),
});

export const Callout = defineComponent({
  name: "Callout",
  description: "A short highlighted note: a key idea, a tip or a common mistake.",
  props: z.object({
    text: z.string().describe("One or two sentences"),
    tone: z.enum(["key", "tip", "warning"]).optional().describe("key (default), tip, or warning"),
  }),
  component: ({ props }) => (
    <aside className={`block callout ${props.tone ?? "key"}`}>
      <span className="eyebrow">{props.tone === "tip" ? "Tip" : props.tone === "warning" ? "Watch out" : "Key idea"}</span>
      <p>{rich(props.text)}</p>
    </aside>
  ),
});

export const Text = defineComponent({
  name: "Text",
  description: "A paragraph between other blocks. Supports **bold** and `code`.",
  props: z.object({ text: z.string() }),
  component: ({ props }) => <p className="block text">{rich(props.text)}</p>,
});

// --- Lesson (root) ---

const blockRefs = [Quiz.ref, MultipleChoice.ref, Flashcards.ref, StepExplainer.ref, Figure.ref, Callout.ref, Text.ref] as const;

export const Lesson = defineComponent({
  name: "Lesson",
  description: "The root: a column of blocks.",
  props: z.object({
    blocks: z.array(z.union(blockRefs)).describe("Blocks in reading order"),
  }),
  component: ({ props, renderNode }) => <div className="lesson">{renderNode(props.blocks)}</div>,
});

export const library = createLibrary({
  components: [Lesson, Quiz, MultipleChoice, Flashcards, Card, StepExplainer, Step, Figure, Callout, Text],
  root: "Lesson",
});
