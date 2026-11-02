# Upstream

This folder is a fork of [LearnHarness](https://github.com/amosblomqvist/learnharness) by Amos Blomqvist and contributors, licensed under the [GNU AGPL-3.0](LICENSE). VisualLearn uses it as its learning engine: the prober, teacher and practice agents, the lesson plan, and the knowledge graph.

| | |
|---|---|
| Upstream commit | `e69e85269c064c6c9c49a8b06ae78c06e79c4a63` (release 0.4.1, 2026-10-31) |
| Imported | 2026-11-02, as a plain copy (`git archive`), with no upstream history |
| Not included | `build/` (desktop app icons and installer art), excluded by this repository's `.gitignore`; the server doesn't use them |

## Licence

LearnHarness is AGPL-3.0, so this folder and the VisualLearn server built on it are AGPL-3.0 too. Anyone who uses the hosted server over a network is entitled to its complete source, including our changes. Keep the `LICENSE` file and the notices in the source files.

## Changes from upstream

None yet. Every change made to this folder is listed here, newest first, so it can be compared with or merged into a newer upstream release.

## Status on this machine (2026-11-02)

- Node 24 on Windows: `tsc --noEmit` clean. Tests: 3,156 of 3,187 pass. The 27 failures are platform or machine specific: Windows paths given where `file://` URLs are expected, POSIX file modes (`0600`), POSIX signals, desktop release scripts, and tests that assume the Claude Code CLI is not installed. Upstream CI runs on Node 26.
- Run as a server with `next dev`, a scratch `LH_DATA_DIR` and `LH_AUTH_FILE`, and Gemini through `GEMINI_API_KEY`. A session started, the prober checked the empty knowledge graph, asked a goal question, took a structured answer through `POST /api/chat`, and posed a probe question.
