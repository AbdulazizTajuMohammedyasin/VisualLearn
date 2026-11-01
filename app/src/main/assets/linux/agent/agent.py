"""VisualLearn figure agent.

Runs inside the Debian guest. Drives Claude through the Anatomy skill to build one
interactive figure for a job directory, and reports progress to the Android app as
JSON lines prefixed with "@@VL " on stdout.

Job directory layout:
    job.json        {"prompt": str, "theme": "dark"|"light"}   (written by the app)
    messages.json   conversation state, saved after every turn (enables resume)
    status.json     latest status snapshot for the app
    work/           the figure's source folder (build script, modules)
    out/            finished pages: figure.html (dark) and figure-light.html
"""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import anthropic

MODEL = os.environ.get("VL_MODEL", "claude-opus-5-5")
EFFORT = os.environ.get("VL_EFFORT", "high")
SKILL_DIR = Path("/opt/anatomy")
MAX_TOOL_OUTPUT = 30_000
MAX_TURNS = 400
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp"}
PRICES = {  # USD per 1M tokens: input, output, cache read, cache write (5m)
    "claude-opus-5-5": (4.00, 20.00, 0.20, 5.00),
    "claude-sonnet-5-5": (2.00, 10.00, 0.20, 2.50),
}


def emit(kind, **data):
    print("@@VL " + json.dumps({"type": kind, **data}), flush=True)


TOOLS = [
    {
        "name": "bash",
        "description": "Run a shell command in the Debian guest (cwd: the job's work/ folder). "
                       "Node 22 is on PATH, CHROME_PATH points at Chromium. Returns combined stdout/stderr.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "timeout_sec": {"type": "integer", "description": "Default 300, max 1800."},
            },
            "required": ["command"],
        },
    },
    {
        "name": "read_file",
        "description": "Read a text file (with line numbers) or an image (PNG/JPEG/WebP, returned as an image you can see). "
                       "Use it to read the skill's references and examples and to look at every screenshot you capture.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "offset": {"type": "integer", "description": "1-based first line (text only)."},
                "limit": {"type": "integer", "description": "Max lines (text only), default 2000."},
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Create or overwrite a text file. Relative paths are inside work/.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace one exact, unique occurrence of old_string with new_string in a text file.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "old_string": {"type": "string"},
                "new_string": {"type": "string"},
            },
            "required": ["path", "old_string", "new_string"],
        },
    },
    {
        "name": "report_progress",
        "description": "Tell the user what stage the figure is at. Call it when you start each workflow step "
                       "and at notable moments, with one short plain sentence.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "stage": {"type": "string", "enum": ["research", "design", "parts", "geometry", "build", "motion", "verify", "polish"]},
                "message": {"type": "string"},
            },
            "required": ["stage", "message"],
        },
    },
    {
        "name": "finish",
        "description": "Deliver the finished figure. Call exactly once, after the pages are built and verified.",
        "eager_input_streaming": True,
        "input_schema": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Short title, at most 60 characters."},
                "summary": {"type": "string", "description": "Two or three sentences: what the object is and what it explains."},
                "dark_page": {"type": "string", "description": "Path of the built dark-theme standalone HTML page."},
                "light_page": {"type": "string", "description": "Path of the built light-theme standalone HTML page."},
            },
            "required": ["title", "summary", "dark_page", "light_page"],
        },
    },
]

REQUIRED = {t["name"]: (t["input_schema"]["required"], t["input_schema"]["properties"]) for t in TOOLS}


def system_prompt(job_dir: Path) -> list:
    skill = (SKILL_DIR / "SKILL.md").read_text()
    env = f"""You are the figure engine inside VisualLearn, an Android app. You run in a Debian 12 (armhf) userland on a phone, through proot, with no human watching the shell. The person who asked for the figure sees only your report_progress messages and the finished pages.

Your job: build one figure with the Anatomy skill below, then call finish.

Environment
- The skill is installed at {SKILL_DIR} (SKILL.md, kit/, references/, scripts/, examples/). Read references and examples from there with read_file.
- Work in {job_dir / 'work'} (the bash cwd). Import the kit by absolute path, e.g. `import * as K from "{SKILL_DIR}/kit/iso-kit.mjs"` and `import {{ kitScript }} from "{SKILL_DIR}/scripts/inline-kit.mjs"`.
- Build framework-free: a Node build script that writes standalone HTML pages (no React). Build BOTH themes: a dark page and a light page (`--light`).
- Node 22 and Chromium 150 are installed; CHROME_PATH is set, so scripts/capture.mjs, drive.mjs and lines.mjs work. The phone is slow: a headless Chrome capture takes tens of seconds, so capture deliberately and run long checks with a generous timeout_sec.
- No network access is needed; do not install packages.

Scope and pacing
- Default to the Figure size (40-80 parts, at most one WebGL layer, no 3D mode) unless the request explicitly asks for more. Never start an Epic.
- Do not ask the user questions; make sensible choices and state them in the summary.
- Follow the skill's workflow and verification, reading every PNG you capture, but stop iterating once the figure is clearly good: the user is waiting on a phone.
- Call report_progress at each workflow step. When the pages are built and checked, call finish with their paths.

The Anatomy skill (SKILL.md) follows.
"""
    return [{"type": "text", "text": env + "\n" + skill}]


def resolve(job_dir: Path, path: str) -> Path:
    p = Path(path)
    return (p if p.is_absolute() else job_dir / "work" / p).resolve()


def writable(job_dir: Path, target: Path) -> bool:
    return any(target.is_relative_to(root) for root in (job_dir.resolve(), Path("/tmp")))


def clip(text: str) -> str:
    if len(text) <= MAX_TOOL_OUTPUT:
        return text
    half = MAX_TOOL_OUTPUT // 2
    return text[:half] + f"\n\n[... {len(text) - MAX_TOOL_OUTPUT} characters omitted ...]\n\n" + text[-half:]


def run_tool(job_dir: Path, name: str, args: dict):
    """Returns (content, is_error, finished_payload_or_None)."""
    if name == "bash":
        timeout = max(1, min(int(args.get("timeout_sec") or 300), 1800))
        try:
            proc = subprocess.run(
                args["command"], shell=True, cwd=job_dir / "work", timeout=timeout,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace",
            )
            return clip(proc.stdout) + f"\n[exit {proc.returncode}]", proc.returncode != 0, None
        except subprocess.TimeoutExpired as e:
            out = e.stdout if isinstance(e.stdout, str) else (e.stdout or b"").decode(errors="replace")
            return clip(out) + f"\n[timed out after {timeout}s]", True, None

    if name == "read_file":
        target = resolve(job_dir, args["path"])
        if not target.is_file():
            return f"No such file: {target}", True, None
        media = IMAGE_TYPES.get(target.suffix.lower())
        if media:
            import base64
            data = base64.standard_b64encode(target.read_bytes()).decode()
            return [{"type": "image", "source": {"type": "base64", "media_type": media, "data": data}}], False, None
        lines = target.read_text(errors="replace").splitlines()
        start = max(1, int(args.get("offset") or 1))
        limit = max(1, int(args.get("limit") or 2000))
        chunk = lines[start - 1:start - 1 + limit]
        body = "\n".join(f"{start + i:6}\t{line}" for i, line in enumerate(chunk))
        more = f"\n[{len(lines) - (start - 1 + len(chunk))} more lines]" if start - 1 + len(chunk) < len(lines) else ""
        return clip(body + more), False, None

    if name in ("write_file", "edit_file"):
        target = resolve(job_dir, args["path"])
        if not writable(job_dir, target):
            return f"Refused: {target} is outside the job folder and /tmp.", True, None
        if name == "write_file":
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(args["content"])
            return f"Wrote {len(args['content'])} characters to {target}", False, None
        if not target.is_file():
            return f"No such file: {target}", True, None
        text = target.read_text()
        count = text.count(args["old_string"])
        if count != 1:
            return f"old_string found {count} times in {target}; it must match exactly once.", True, None
        target.write_text(text.replace(args["old_string"], args["new_string"], 1))
        return f"Edited {target}", False, None

    if name == "report_progress":
        emit("progress", stage=args["stage"], message=args["message"])
        return "ok", False, None

    if name == "finish":
        out = job_dir / "out"
        out.mkdir(exist_ok=True)
        pages = {}
        for key, dest in (("dark_page", "figure.html"), ("light_page", "figure-light.html")):
            src = resolve(job_dir, args[key])
            if not src.is_file():
                return f"{key} not found: {src}. Build the page first.", True, None
            shutil.copyfile(src, out / dest)
            pages[key] = str(out / dest)
        result = {"title": args["title"][:80], "summary": args["summary"], **pages}
        (out / "result.json").write_text(json.dumps(result))
        return "Delivered.", False, result

    return f"Unknown tool {name}", True, None


def validate(name: str, args) -> str | None:
    if name not in REQUIRED:
        return f"unknown tool {name}"
    if not isinstance(args, dict):
        return "input is not an object"
    required, props = REQUIRED[name]
    for key in required:
        if key not in args:
            return f"missing required field {key}"
    for key, value in args.items():
        kind = props.get(key, {}).get("type")
        if kind == "string" and not isinstance(value, str):
            return f"{key} must be a string"
        if kind == "integer" and not isinstance(value, int):
            return f"{key} must be an integer"
    return None


class Usage:
    def __init__(self, data: dict):
        self.d = {k: int(data.get(k, 0)) for k in ("input", "output", "cache_read", "cache_write")}

    def add(self, u):
        self.d["input"] += u.input_tokens or 0
        self.d["output"] += u.output_tokens or 0
        self.d["cache_read"] += getattr(u, "cache_read_input_tokens", 0) or 0
        self.d["cache_write"] += getattr(u, "cache_creation_input_tokens", 0) or 0

    def cost(self) -> float:
        p = PRICES.get(MODEL, PRICES["claude-opus-5-5"])
        d = self.d
        return (d["input"] * p[0] + d["output"] * p[1] + d["cache_read"] * p[2] + d["cache_write"] * p[3]) / 1e6


def write_status(job_dir: Path, **status):
    path = job_dir / "status.json"
    current = json.loads(path.read_text()) if path.exists() else {}
    current.update(status, updated=int(time.time()))
    path.write_text(json.dumps(current))


def main():
    job_dir = Path(sys.argv[1]).resolve()
    job = json.loads((job_dir / "job.json").read_text())
    (job_dir / "work").mkdir(parents=True, exist_ok=True)
    state_path = job_dir / "messages.json"

    if state_path.exists():
        state = json.loads(state_path.read_text())
        messages, usage = state["messages"], Usage(state.get("usage", {}))
        emit("progress", stage="research", message="Resuming where the last run stopped.")
    else:
        theme = job.get("theme", "dark")
        messages = [{"role": "user", "content":
                     f"Build a figure for this request:\n\n{job['prompt']}\n\n"
                     f"The app will show the {theme} page by default."}]
        usage = Usage({})

    client = anthropic.Anthropic(max_retries=4)
    system = system_prompt(job_dir)
    write_status(job_dir, state="running", pid=os.getpid())
    started = time.time()
    json_retries = 0

    def save():
        state_path.write_text(json.dumps({"messages": messages, "usage": usage.d}))

    for turn in range(MAX_TURNS):
        try:
            with client.beta.messages.stream(
                model=MODEL,
                max_tokens=64000,
                system=system,
                tools=TOOLS,
                messages=messages,
                cache_control={"type": "ephemeral"},
                output_config={"effort": EFFORT},
                context_management={"edits": [{"type": "compact_20260112"}]},
                fallbacks="default",
                betas=["compact-2026-01-12", "server-side-fallback-2026-07-01"],
            ) as stream:
                for event in stream:
                    pass
                response = stream.get_final_message()
            json_retries = 0
        except ValueError:
            json_retries += 1
            if json_retries > 2:
                raise
            continue

        usage.add(response.usage)
        emit("usage", tokens=usage.d, cost_usd=round(usage.cost(), 4), elapsed_sec=int(time.time() - started))
        assistant = {"role": "assistant", "content": [b.to_dict() for b in response.content]}

        if response.stop_reason == "pause_turn":
            messages.append(assistant)
            continue
        if response.stop_reason == "refusal":
            emit("error", message="Claude declined this request.")
            write_status(job_dir, state="failed", error="refusal")
            return 2

        tool_uses = [b for b in response.content if b.type == "tool_use"]
        if not tool_uses:
            messages.append(assistant)
            messages.append({"role": "user", "content":
                             "Keep going until the figure is built and verified, then call finish."})
            save()
            continue
        if response.stop_reason == "max_tokens":
            messages.append(assistant)
            messages.append({"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": b.id, "is_error": True,
                 "content": "Your output hit max_tokens and this tool input was cut off. Retry with a smaller step "
                            "(e.g. write the file in parts)."} for b in tool_uses]})
            save()
            continue

        results, finished = [], None
        for block in tool_uses:
            problem = validate(block.name, block.input)
            if problem:
                results.append({"type": "tool_result", "tool_use_id": block.id, "is_error": True,
                                "content": f"INVALID_INPUT: {problem}"})
                continue
            emit("tool", name=block.name, detail=str(block.input.get("command") or block.input.get("path") or "")[:200])
            try:
                content, is_error, payload = run_tool(job_dir, block.name, block.input)
            except Exception as e:  # report tool crashes to the model instead of dying
                content, is_error, payload = f"{type(e).__name__}: {e}", True, None
            results.append({"type": "tool_result", "tool_use_id": block.id, "content": content, "is_error": is_error})
            finished = finished or payload

        messages.append(assistant)
        messages.append({"role": "user", "content": results})
        save()

        if finished:
            write_status(job_dir, state="done", result=finished, cost_usd=round(usage.cost(), 4))
            emit("done", **finished, cost_usd=round(usage.cost(), 4))
            return 0

    emit("error", message="Stopped after the maximum number of steps without finishing.")
    write_status(job_dir, state="failed", error="max_turns")
    return 3


def fail(code: int, message: str):
    emit("error", message=message)
    write_status(Path(sys.argv[1]).resolve(), state="failed", error=message)
    sys.exit(code)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except anthropic.AuthenticationError:
        fail(4, "The Claude API key was rejected. Check it in Settings.")
    except anthropic.RateLimitError:
        fail(5, "Rate limited by the Claude API. Resume the job in a minute.")
    except anthropic.APIConnectionError:
        fail(6, "Lost the connection to the Claude API. Resume the job when online.")
    except anthropic.APIStatusError as e:
        fail(7, f"Claude API error {e.status_code}: {e.message}")
