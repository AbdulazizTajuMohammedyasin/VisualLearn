import { createContext, useContext, type ReactNode } from "react";

/** Messages from the page to the app (window.VLBridge.postMessage). */
export type HostMessage =
  | { type: "ready" }
  | { type: "height"; height: number }
  | { type: "state"; state: Record<string, unknown> }
  | { type: "action"; name: string; message: string; context: Record<string, unknown> }
  | { type: "openFigure"; figureId: string };

declare global {
  interface Window {
    VLBridge?: { postMessage(json: string): void };
  }
}

export function post(message: HostMessage) {
  const json = JSON.stringify(message);
  if (window.VLBridge) window.VLBridge.postMessage(json);
  else console.log("[host]", json);
}

/**
 * Component state the app keeps for us (answers, card positions), so it survives
 * the WebView being recreated while scrolling or after a restart.
 */
export interface Saved {
  get<T>(key: string, initial: T): T;
  set(key: string, value: unknown): void;
}

const SavedContext = createContext<Saved | null>(null);

export function SavedProvider({ value, children }: { value: Saved; children: ReactNode }) {
  return <SavedContext.Provider value={value}>{children}</SavedContext.Provider>;
}

export function useSavedStore(): Saved {
  const saved = useContext(SavedContext);
  if (!saved) throw new Error("useSaved outside SavedProvider");
  return saved;
}

export function useSaved<T>(key: string, initial: T): [T, (value: T) => void] {
  const saved = useSavedStore();
  return [saved.get(key, initial), (value: T) => saved.set(key, value)];
}

/** Sends a follow-up to the assistant; it arrives on the server as a message's `uiAction`. */
export function askAssistant(name: string, message: string, context: Record<string, unknown>) {
  post({ type: "action", name, message, context });
}
