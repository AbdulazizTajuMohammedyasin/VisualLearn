/**
 * Splits streamed model text into message parts: Markdown text, and OpenUI Lang
 * programs the model writes inside ```openui fences. Works on arbitrary chunk
 * boundaries: text that might be the start of a fence marker is held back until
 * the next chunk shows what it is.
 */
export type FenceEvent =
  | { type: "text-start" }
  | { type: "text"; text: string }
  | { type: "ui-start" }
  | { type: "ui"; program: string };

const OPEN = "```openui\n";
const CLOSE = "\n```";

export class FenceSplitter {
  private buffer = "";
  private inUi = false;
  /** Whether the current text part has received anything yet (a new one starts lazily). */
  private textOpen = false;

  push(chunk: string): FenceEvent[] {
    this.buffer += chunk;
    return this.drain(false);
  }

  /** Flushes whatever is held back at the end of the stream. */
  end(): FenceEvent[] {
    return this.drain(true);
  }

  private drain(final: boolean): FenceEvent[] {
    const out: FenceEvent[] = [];
    while (this.buffer.length) {
      const marker = this.inUi ? CLOSE : OPEN;
      const at = this.buffer.indexOf(marker);
      if (at >= 0) {
        this.emit(out, this.buffer.slice(0, at));
        this.buffer = this.buffer.slice(at + marker.length);
        if (this.inUi) {
          // Drop the rest of the closing fence's line.
          this.buffer = this.buffer.replace(/^[^\n]*\n?/, "");
          this.inUi = false;
          this.textOpen = false;
        } else {
          this.inUi = true;
          out.push({ type: "ui-start" });
        }
        continue;
      }
      // Keep back a tail that could be the beginning of the marker.
      const keep = final ? 0 : partialSuffix(this.buffer, marker);
      this.emit(out, this.buffer.slice(0, this.buffer.length - keep));
      this.buffer = this.buffer.slice(this.buffer.length - keep);
      break;
    }
    return out;
  }

  private emit(out: FenceEvent[], text: string) {
    if (!text) return;
    if (this.inUi) {
      out.push({ type: "ui", program: text });
    } else {
      if (!this.textOpen) {
        // Whitespace alone between blocks doesn't deserve its own part.
        if (!text.trim()) return;
        text = text.replace(/^\s+/, "");
        out.push({ type: "text-start" });
        this.textOpen = true;
      }
      out.push({ type: "text", text });
    }
  }
}

/** Length of the longest suffix of [text] that is a proper prefix of [marker]. */
function partialSuffix(text: string, marker: string): number {
  for (let n = Math.min(marker.length - 1, text.length); n > 0; n--) {
    if (marker.startsWith(text.slice(text.length - n))) return n;
  }
  return 0;
}
