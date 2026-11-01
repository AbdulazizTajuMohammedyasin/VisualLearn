import { Renderer } from "@openuidev/react-lang";
import { useMemo, useSyncExternalStore } from "react";
import { createRoot } from "react-dom/client";
import { post, SavedProvider, type Saved } from "./host";
import { library, LIBRARY_VERSION } from "./library";
import "./styles.css";

/** What the app passes to `VisualLearn.render(...)`. */
interface RenderInput {
  program: string;
  streaming: boolean;
  dark: boolean;
  /** Saved component state from a previous session of this message part. */
  state?: Record<string, unknown>;
}

let input: RenderInput | null = null;
let state: Record<string, unknown> = {};
let version = 0;
const listeners = new Set<() => void>();

function changed() {
  version++;
  listeners.forEach((listener) => listener());
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

function App() {
  const v = useSyncExternalStore(subscribe, () => version);
  // A new store object per change so every component reading saved state re-renders.
  const saved = useMemo<Saved>(
    () => ({
      get: <T,>(key: string, initial: T) => (key in state ? (state[key] as T) : initial),
      set: (key, value) => {
        state = { ...state, [key]: value };
        post({ type: "state", state });
        changed();
      },
    }),
    [v],
  );
  if (!input) return null;
  return (
    <SavedProvider value={saved}>
      <Renderer
        response={input.program}
        library={library}
        isStreaming={input.streaming}
        onAction={(event) =>
          post({
            type: "action",
            name: event.type,
            message: event.humanFriendlyMessage,
            context: { params: event.params, formState: event.formState ?? null },
          })
        }
        onError={(errors) => errors.length && console.warn("openui", JSON.stringify(errors))}
      />
    </SavedProvider>
  );
}

declare global {
  interface Window {
    VisualLearn: { version: string; render(input: RenderInput): void };
  }
}

window.VisualLearn = {
  version: LIBRARY_VERSION,
  render(next) {
    if (next.state && input === null) state = next.state;
    document.documentElement.dataset.theme = next.dark ? "dark" : "light";
    input = next;
    changed();
  },
};

const container = document.getElementById("root")!;
createRoot(container).render(<App />);

// The app sizes the WebView to its content, so it never scrolls inside the chat.
let lastHeight = 0;
new ResizeObserver(() => {
  const height = Math.ceil(container.getBoundingClientRect().height);
  if (height !== lastHeight) {
    lastHeight = height;
    post({ type: "height", height });
  }
}).observe(container);

post({ type: "ready" });
