# VisualLearn server API (draft v1.1)

Status: draft for review. Nothing here is implemented on the server yet. v1.1 records the product decisions of 2026-11-01 (section 12).

VisualLearn moves all heavy work to the server. The Android app signs in, sends chat messages, starts figure jobs, follows their progress, and renders finished results. It no longer runs Linux, Node, Chromium or an agent on the phone.

This document defines the contract between the two. It is written so the app and the server can be built in parallel: the app ships a fake implementation of this API for development (see "Fake server" at the end).

## 1. What the server owns

| Area | Responsibility |
|---|---|
| Accounts | Sign-in with Google or an emailed code, sessions, account deletion. Users never bring their own model API keys. |
| Chat | Runs the model, streams replies. Replies can contain text, OpenUI interactive components, and figure references. |
| Figure jobs | Runs the Anatomy skill with OpenCode in an isolated worker (Node 22, Chromium), stores the finished pages, reports progress. |
| Scheduling | Usage is unlimited. Work is queued and shared fairly across users according to the capacity available (section 6). |
| Model routing | The admin decides which model serves chat and figures, local or through a provider API (section 9). Users don't pick models. |
| Storage | Conversations and figures are kept indefinitely. Figure pages (HTML) and preview images live in object storage, served through short-lived signed URLs. |
| Push | Sends "figure ready" notifications through Firebase Cloud Messaging. |

## 2. Conventions

- Base URL: `https://api.<domain>/v1`. Breaking changes get a new version prefix.
- JSON in and out, UTF-8, `camelCase` fields, timestamps as ISO 8601 UTC strings.
- Every request carries `Authorization: Bearer <accessToken>` (except auth endpoints), `X-App-Version: <versionName>` and `X-UI-Library-Version: <semver>` (section 8).
- IDs are opaque strings. The client never parses them.
- Lists are paginated with `?cursor=<opaque>&limit=<1..100>`; responses include `nextCursor` (null at the end).
- Writes that create something accept an optional `Idempotency-Key` header (a client-generated UUID) so retries on flaky mobile networks don't create duplicates.

### Errors

Every non-2xx response has this body:

```json
{ "error": { "code": "rate_limited", "message": "You're sending messages too quickly. Try again in a few seconds.", "retryable": true } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `invalid_request` | Malformed body or parameters. |
| 401 | `unauthenticated` | Missing or expired access token; refresh and retry once. |
| 403 | `forbidden` | Not this user's resource, or an admin endpoint. |
| 404 | `not_found` | Includes items an admin deleted. |
| 409 | `conflict` | E.g. cancelling a job that already finished. |
| 426 | `upgrade_required` | App or UI library version no longer supported. |
| 429 | `rate_limited` | Abuse protection only (requests per minute), never a usage cap. `Retry-After` header. |
| 5xx | `internal` / `unavailable` | Retryable with backoff. |

`message` is safe to show to the user.

## 3. Auth

Two ways to sign in: Google, and a 6-digit code sent by email. Both end in the same VisualLearn tokens. The app keeps tokens encrypted with the Android Keystore.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/session` | `{ "provider": "google", "idToken": "…" }` | `Session` |
| POST | `/auth/email/start` | `{ "email": "…" }` | 204; a code is emailed (valid 10 min) |
| POST | `/auth/session` | `{ "provider": "email", "email": "…", "code": "123456" }` | `Session` |
| POST | `/auth/refresh` | `{ "refreshToken": "…" }` | `Session` (refresh tokens rotate) |
| DELETE | `/auth/session` | `{ "refreshToken": "…" }` | 204 (sign out) |
| GET | `/me` | | `{ user }` |
| DELETE | `/me` | | 202; deletes the account and all its data (required by Google Play) |

```json
{
  "accessToken": "…", "accessTokenExpiresAt": "2026-11-01T15:00:00Z", "refreshToken": "…",
  "user": { "id": "usr_…", "displayName": "Abdulaziz", "email": "…", "avatarUrl": "…", "role": "user" }
}
```

- The Google `idToken` comes from Android Credential Manager (Sign in with Google), issued for the server's Google OAuth web client ID; the server verifies it with Google.
- Email codes: at most 5 attempts per code and a resend cooldown of 60 s (`429` otherwise).
- `role` is `user` or `admin`. Admins use the admin API (section 9); the app has no admin screens.

## 4. Chat

### Messages

A message is a list of parts so one reply can mix text, interactive UI and figures.

```json
{
  "id": "msg_…",
  "role": "assistant",
  "status": "complete",
  "createdAt": "2026-11-01T14:02:11Z",
  "parts": [
    { "type": "text", "text": "A zipper locks because each tooth…" },
    { "type": "ui", "program": "root = Quiz(...)\n…", "libraryVersion": "1.0.0" },
    { "type": "figure", "figureId": "fig_…" }
  ]
}
```

| Part | Fields | Rendered by |
|---|---|---|
| `text` | `text` (Markdown) | Native Compose |
| `ui` | `program` (OpenUI Lang), `libraryVersion` | The OpenUI WebView (section 8) |
| `figure` | `figureId` | A figure card; opens the figure viewer |

`status` is `streaming`, `complete` or `failed` (with `error`).

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/conversations` | | `{ items: [ { id, title, updatedAt } ], nextCursor }` |
| POST | `/conversations` | `{}` | `{ id, title, createdAt, updatedAt }` |
| GET | `/conversations/{id}` | | `{ id, title, updatedAt, messages: [Message], nextCursor }` (newest last) |
| PATCH | `/conversations/{id}` | `{ "title": "…" }` | conversation |
| DELETE | `/conversations/{id}` | | 204 |
| POST | `/conversations/{id}/messages` | see below | `{ userMessage, assistantMessage }` with `assistantMessage.status = "streaming"` |
| GET | `/messages/{id}/events` | | SSE stream (below) |
| POST | `/messages/{id}/stop` | | 204; the reply ends with what it has |

Sending a message:

```json
{ "text": "How does a zipper work?", "mode": "auto", "uiAction": null }
```

- `mode`: `auto` (the model decides whether to add UI or start a figure), or `figure` (the user pressed the Figure button: start a figure job for this request).
- `uiAction`: set when an OpenUI component sends a follow-up (`@ToAssistant`), e.g. "Explain my wrong answers". Shape: `{ "sourceMessageId": "msg_…", "name": "review_mistakes", "context": { … component state … } }`. The server turns it into the next user turn; `text` can then be empty.

### Reply stream (Server-Sent Events)

`GET /messages/{id}/events` streams the assistant reply. Each event has an `id:` so the client can reconnect with `Last-Event-ID` and receive only what it missed; the server keeps events for a finished message for at least 10 minutes. If the message is already complete, the stream replays every event and closes.

| `event:` | `data:` | Meaning |
|---|---|---|
| `part.start` | `{ "partIndex": 2, "type": "figure", "figureId": "fig_…" }` | A new part begins. Text and ui parts also start this way. |
| `text.delta` | `{ "partIndex": 0, "text": "A zip" }` | Append to a text part. |
| `ui.delta` | `{ "partIndex": 1, "program": "q1 = …\n" }` | Append OpenUI Lang to a ui part; the renderer draws it as it arrives. |
| `message.done` | `{ "message": Message }` | Final, authoritative message. Replace whatever was built from deltas. |
| `message.error` | `{ "error": { … } }` | The reply failed; partial parts stay visible. |
| `ping` | `{}` | Keep-alive every 15 s. |

## 5. Figure jobs

A figure job runs the Anatomy skill for one prompt. Once a worker picks it up, it takes about 20 to 75 minutes.

```json
{
  "id": "fig_…",
  "prompt": "How a pin tumbler lock opens",
  "status": "queued",
  "queuePosition": 3,
  "estimatedStartAt": "2026-11-01T14:20:00Z",
  "stage": null,
  "message": "Waiting for a free worker.",
  "progress": 0,
  "title": null,
  "summary": null,
  "conversationId": "conv_…",
  "createdAt": "…", "updatedAt": "…", "startedAt": null, "finishedAt": null,
  "error": null,
  "pages": null,
  "previewUrl": null
}
```

- `status`: `queued`, `running`, `succeeded`, `failed`, `cancelled`.
- `queuePosition` and `estimatedStartAt` are set while `queued` (section 6) and null otherwise.
- `stage`: `research`, `design`, `parts`, `geometry`, `build`, `motion`, `verify`, `polish` (from the agent's progress reports; the server maps them to `progress`).
- When `succeeded`, `title`, `summary`, `pages` and `previewUrl` are set:

```json
"pages": {
  "dark":  { "url": "https://cdn…/fig_…/dark.html?sig=…",  "sha256": "…", "bytes": 136488, "expiresAt": "…" },
  "light": { "url": "https://cdn…/fig_…/light.html?sig=…", "sha256": "…", "bytes": 136489, "expiresAt": "…" }
}
```

Every figure is stored both on the server (indefinitely) and on the phone: the app downloads both pages as soon as a figure succeeds, verifies `sha256`, and keeps them, so figures open offline. URLs are signed and short-lived; fetch the figure again for fresh ones.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/figures` | `{ prompt, theme: "dark"\|"light", conversationId? }` | 202, Figure |
| GET | `/figures` | | `{ items: [Figure], nextCursor }`, newest first |
| GET | `/figures/{id}` | | Figure (fresh signed URLs) |
| GET | `/figures/{id}/events` | | SSE: `figure.update` with the full Figure on every change (including queue position); `ping` keep-alive; closes after a terminal status |
| POST | `/figures/{id}/cancel` | | Figure |
| POST | `/figures/{id}/retry` | | Figure (resumes from the saved OpenCode session when possible) |
| DELETE | `/figures/{id}` | | 204 |

Figures started from chat (a `figure` part) are the same resources.

## 6. Scheduling (unlimited usage)

There are no per-user quotas or paid plans. Instead the server shares whatever capacity it has:

- **Figure workers** are a pool sized to the hardware (and, for API models, to provider rate limits). Jobs wait in a queue when all workers are busy.
- **Fair share:** the queue is ordered round-robin across users, so one person's tenth job never waits ahead of someone else's first. A user's jobs may run in parallel only when there is idle capacity; under load each user has at most one running job.
- **Chat** runs on its own pool so figure load never slows conversations.
- **Visibility:** queued figures report `queuePosition` and `estimatedStartAt`, recomputed as capacity changes, so the app can say "3rd in line, starts in about 12 min".
- **Abuse protection** is separate from usage: request rate limits (`429 rate_limited`) and a cap on queued figures per user (e.g. 10) stop scripts from flooding the queue.

## 7. Sync and offline

Chat is readable without internet; sending needs a connection. The app keeps a local copy of conversations, messages and figure metadata, plus every finished figure's pages.

| Method | Path | Response |
|---|---|---|
| GET | `/sync?since=<cursor>` | `{ conversations: [ { id, title, updatedAt } ], deletedConversationIds: [], figures: [Figure], deletedFigureIds: [], cursor }` |

- First sync: omit `since`; the server returns everything and a `cursor`. Later syncs return only what changed since, including deletions made by the user on another device or by an admin, which the app then removes from the phone.
- The app syncs on launch, on reconnect, and after sending; it fetches full conversations (`GET /conversations/{id}`) when they change.
- Offline, the app shows cached chats and figures, disables the composer, and labels the screen as offline.

## 8. OpenUI (interactive components)

Replies can include interactive UI written in OpenUI Lang (github.com/thesysdev/openui). The model may only use the components of the VisualLearn library:

| Component | What it does |
|---|---|
| `Lesson` | The root: a column of blocks. |
| `Quiz` | Several `MultipleChoice` questions, a score, and "Explain my mistakes" (sends a `uiAction` named `review_mistakes`). |
| `MultipleChoice` | One question with instant feedback and an explanation. |
| `Flashcards` / `Card` | A deck: tap to flip, mark "knew it" or "again"; missed cards come back in another round. |
| `StepExplainer` / `Step` | A process, one step at a time with back and next. |
| `Figure` | A reference to a figure job; "Open figure" opens it in the app. |
| `Callout` | A key idea, tip or warning. |
| `Text` | A short paragraph between blocks. |

OpenUI's own component set (`@openuidev/react-ui`: tables, charts, tabs) is not in library 1.0.0; adding it is a library version bump.

The library is one versioned package, `ui-library/` in the app repo, shared by the server and the app. `npm run prompt` writes the system prompt section the server gives the chat model (`dist/system-prompt.md`); `npm run build` writes the app's offline renderer page. Answers and card positions are kept on the phone per message part. The app sends `X-UI-Library-Version`; the server only emits programs the app can render, or answers `426` if the app is too old.

Interactions such as answering a quiz run entirely in the app. Only `@ToAssistant` actions reach the server, as a `uiAction` on the next message.

## 9. Admin API

For users with `role: "admin"`, used from an admin console (web, out of scope for the app). Every call is audited.

### Model routing

The admin decides which model serves each kind of work, either a local model on VisualLearn's own hardware or a provider API. Users never choose.

| Method | Path | Body / response |
|---|---|---|
| GET | `/admin/providers` | `{ items: [Provider] }` |
| PUT | `/admin/providers/{id}` | `Provider` (create or update) |
| DELETE | `/admin/providers/{id}` | 204 (refused while a route uses it) |
| GET | `/admin/routing` | `Routing` |
| PUT | `/admin/routing` | `Routing`; takes effect for new chats and new figure jobs |

```json
{ "id": "anthropic", "kind": "api", "npm": "@ai-sdk/anthropic", "apiKey": "••••", "models": ["claude-opus-5-5", "claude-sonnet-5-5"] }
{ "id": "lab-gpu", "kind": "local", "npm": "@ai-sdk/openai-compatible", "baseUrl": "http://10.0.0.5:8000/v1", "models": ["qwen3-coder-480b"] }
```

```json
{
  "chat":    { "model": "anthropic/claude-sonnet-5-5", "fallback": "lab-gpu/qwen3-coder-480b" },
  "figures": { "model": "anthropic/claude-opus-5-5",   "fallback": null }
}
```

- Model IDs use OpenCode's `provider/model` form. Each figure job's OpenCode config is generated from the routing at job start: API providers get their key through the worker's environment; local providers are OpenAI-compatible endpoints (vLLM, Ollama, LM Studio) declared with `baseURL`.
- `fallback` is used when the primary is unreachable or rate limited.
- API keys are write-only: responses mask them.
- Note: Anatomy needs a strong agentic model for a long session; local models are likely to suit chat better than figures.

### Content and accounts

| Method | Path | Notes |
|---|---|---|
| GET | `/admin/users?query=…` | Search users |
| GET | `/admin/users/{id}` | Profile, counts, last active |
| DELETE | `/admin/users/{id}` | Deletes the account and all its data |
| GET | `/admin/users/{id}/conversations` | |
| DELETE | `/admin/conversations/{id}` | |
| GET | `/admin/figures?status=…` | |
| DELETE | `/admin/figures/{id}` | Removes the record and stored pages |
| GET | `/admin/capacity` | Workers busy/idle, queue length, wait estimate, provider rate-limit headroom |

Deletions reach users' phones through sync (section 7).

## 10. Retention

- Conversations, messages and figures are kept indefinitely.
- A user can delete a chat, a figure, or their whole account (`DELETE /me`); an admin can delete any of these at any time. Deletion removes the database rows, the stored pages and previews, and (through sync) the copies on the user's phones.
- The privacy policy states this; Google Play requires in-app account deletion and a web link for it.

## 11. Server architecture

- API service (stateless) + Postgres (users, conversations, messages, figures, routing) + object storage (pages, previews) + a job queue with fair-share ordering.
- Figure workers run each job in its own container: Node 22, Chromium, the Anatomy skill pinned to a commit, and OpenCode (`opencode run --format json --auto`, with stdin closed or it waits for input). The Anatomy skill is linked into `~/.config/opencode/skills/anatomy`; custom tools `report_progress` and `finish` (`~/.config/opencode/tools/`) report stages and deliver pages, the contract proven in the on-device prototype (tag `on-device-poc`).
- Chat runs the routed model with the OpenUI system prompt and a `create_figure` tool that starts a figure job.
- Email codes need a transactional email service (e.g. Amazon SES, Postmark or Resend).

## 12. Decisions (2026-11-01)

| Question | Decision |
|---|---|
| Sign-in | Google and emailed codes. |
| Quotas | Unlimited free use; capacity is shared dynamically (section 6). |
| Figure agent and models | OpenCode. The admin chooses the models and routes them to local hardware or provider APIs; users don't pick. |
| Retention | Kept indefinitely; admins can delete anything at any time; users can delete their own data. |
| Offline | Chat history is readable offline; figures are stored on the server and on the phone. |

Still open: the email provider, the admin console's form (web app or internal tool), and the local inference hardware.

## Fake server

Until the backend exists, debug builds of the app use an in-process fake of this API: it streams canned replies (including a sample OpenUI quiz) and runs simulated figure jobs, with a queue phase, that finish with a bundled example figure. It accepts any Google or email sign-in. It is selected in debug builds only and never ships in release.
