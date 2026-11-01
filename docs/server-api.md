# VisualLearn server API (draft v1)

Status: draft for review. Nothing here is implemented on the server yet.

VisualLearn moves all heavy work to the server. The Android app signs in, sends chat messages, starts figure jobs, follows their progress, and renders finished results. It no longer runs Linux, Node, Chromium or an agent on the phone.

This document defines the contract between the two. It is written so the app and the server can be built in parallel: the app ships a fake implementation of this API for development (see "Fake server" at the end).

## 1. What the server owns

| Area | Responsibility |
|---|---|
| Accounts | Sign-in, sessions, quotas. Users no longer bring their own model API keys. |
| Chat | Runs the model, streams replies. Replies can contain text, OpenUI interactive components, and figure references. |
| Figure jobs | Runs the Anatomy skill in an isolated worker (Node 22, Chromium, the agent harness), stores the finished pages, reports progress. |
| Storage | Finished figure pages (HTML) and preview images in object storage, served through short-lived signed URLs. |
| Push | Sends "figure ready" notifications through Firebase Cloud Messaging. |

## 2. Conventions

- Base URL: `https://api.<domain>/v1`. Breaking changes get a new version prefix.
- JSON in and out, UTF-8, `camelCase` fields, timestamps as ISO 8601 UTC strings.
- Every request carries `Authorization: Bearer <accessToken>` (except auth endpoints), `X-App-Version: <versionName>` and `X-UI-Library-Version: <semver>` (section 7).
- IDs are opaque strings. The client never parses them.
- Lists are paginated with `?cursor=<opaque>&limit=<1..100>`; responses include `nextCursor` (null at the end).
- Writes that create something accept an optional `Idempotency-Key` header (a client-generated UUID) so retries on flaky mobile networks don't create duplicates.

### Errors

Every non-2xx response has this body:

```json
{ "error": { "code": "quota_exceeded", "message": "You have used all 5 figures for today.", "retryable": false } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `invalid_request` | Malformed body or parameters. |
| 401 | `unauthenticated` | Missing or expired access token; refresh and retry once. |
| 403 | `forbidden` | Not this user's resource. |
| 404 | `not_found` | |
| 409 | `conflict` | E.g. cancelling a job that already finished. |
| 426 | `upgrade_required` | App or UI library version no longer supported. |
| 429 | `rate_limited` / `quota_exceeded` | `Retry-After` header when retryable. |
| 5xx | `internal` / `unavailable` | Retryable with backoff. |

`message` is safe to show to the user.

## 3. Auth

Sign-in uses a third-party identity provider (proposal: Google Sign-In, with email magic link as a fallback; to decide). The app gets an ID token from the provider and exchanges it for VisualLearn tokens.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/session` | `{ "provider": "google", "idToken": "..." }` | `{ accessToken, accessTokenExpiresAt, refreshToken, user }` |
| POST | `/auth/refresh` | `{ "refreshToken": "..." }` | same as above (refresh tokens rotate) |
| DELETE | `/auth/session` | `{ "refreshToken": "..." }` | 204 |
| GET | `/me` | | `{ user, quota }` |

```json
{
  "user": { "id": "usr_…", "displayName": "Abdulaziz", "email": "…", "avatarUrl": "…" },
  "quota": { "plan": "free", "figuresRemaining": 3, "figuresPerPeriod": 5, "resetsAt": "2026-11-02T00:00:00Z" }
}
```

The app keeps tokens encrypted with the Android Keystore (as it does the API key today).

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
| `ui` | `program` (OpenUI Lang), `libraryVersion` | The OpenUI WebView (section 7) |
| `figure` | `figureId` | A figure card; opens the figure viewer |

`status` is `streaming`, `complete` or `failed` (with `error`).

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/conversations` | | `{ items: [ { id, title, updatedAt } ], nextCursor }` |
| POST | `/conversations` | `{}` | `{ id, title, createdAt }` |
| GET | `/conversations/{id}` | | `{ id, title, messages: [Message], nextCursor }` (newest last) |
| PATCH | `/conversations/{id}` | `{ "title": "…" }` | conversation |
| DELETE | `/conversations/{id}` | | 204 |
| POST | `/conversations/{id}/messages` | see below | `{ userMessage, assistantMessage }` with `assistantMessage.status = "streaming"` |
| GET | `/messages/{id}/events` | | SSE stream (below) |
| POST | `/messages/{id}/stop` | | 204; the reply ends with what it has |

Sending a message:

```json
{
  "text": "How does a zipper work?",
  "mode": "auto",
  "uiAction": null
}
```

- `mode`: `auto` (the model decides whether to add UI or start a figure), or `figure` (the user pressed the Figure button: start a figure job for this request).
- `uiAction`: set when an OpenUI component sends a follow-up (`@ToAssistant`), e.g. "Explain my wrong answers". Shape: `{ "sourceMessageId": "msg_…", "name": "review_mistakes", "context": { … component state … } }`. The server turns it into the next user turn; `text` can then be empty.

### Reply stream (Server-Sent Events)

`GET /messages/{id}/events` streams the assistant reply. Each event has an `id:` so the client can reconnect with `Last-Event-ID` and receive only what it missed; the server keeps events for a finished message for at least 10 minutes. If the message is already complete, the stream replays every event and closes.

| `event:` | `data:` | Meaning |
|---|---|---|
| `text.delta` | `{ "partIndex": 0, "text": "A zip" }` | Append to a text part. |
| `ui.delta` | `{ "partIndex": 1, "program": "q1 = …\n" }` | Append OpenUI Lang to a ui part; the renderer draws it as it arrives. |
| `part.start` | `{ "partIndex": 2, "type": "figure", "figureId": "fig_…" }` | A new part begins. Text and ui parts also start this way. |
| `message.done` | `{ "message": Message }` | Final, authoritative message. Replace whatever was built from deltas. |
| `message.error` | `{ "error": { … } }` | The reply failed; partial parts stay visible. |
| `ping` | `{}` | Keep-alive every 15 s. |

## 5. Figure jobs

A figure job runs the Anatomy skill for one prompt. On the current workers this takes about 20 to 75 minutes.

```json
{
  "id": "fig_…",
  "prompt": "How a pin tumbler lock opens",
  "status": "running",
  "stage": "geometry",
  "message": "Building the plug, pins and springs.",
  "progress": 0.45,
  "title": null,
  "summary": null,
  "conversationId": "conv_…",
  "createdAt": "…", "startedAt": "…", "finishedAt": null,
  "error": null,
  "pages": null,
  "previewUrl": null
}
```

- `status`: `queued`, `running`, `succeeded`, `failed`, `cancelled`.
- `stage`: `research`, `design`, `parts`, `geometry`, `build`, `motion`, `verify`, `polish` (from the agent's progress reports; the server may map them to `progress`).
- When `succeeded`, `title`, `summary`, `pages` and `previewUrl` are set:

```json
"pages": {
  "dark":  { "url": "https://cdn…/fig_…/dark.html?sig=…",  "sha256": "…", "bytes": 136488, "expiresAt": "…" },
  "light": { "url": "https://cdn…/fig_…/light.html?sig=…", "sha256": "…", "bytes": 136489, "expiresAt": "…" }
}
```

URLs are signed and short-lived. The app downloads both pages once, verifies `sha256`, and caches them by hash, so finished figures open offline.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/figures` | `{ prompt, theme: "dark"\|"light", conversationId? }` | 202, Figure |
| GET | `/figures` | | `{ items: [Figure], nextCursor }`, newest first |
| GET | `/figures/{id}` | | Figure (fresh signed URLs) |
| GET | `/figures/{id}/events` | | SSE: `figure.update` with the full Figure on every change; `ping` keep-alive; closes after a terminal status |
| POST | `/figures/{id}/cancel` | | Figure |
| POST | `/figures/{id}/retry` | | Figure (resumes from the saved agent session when possible) |
| DELETE | `/figures/{id}` | | 204 |

Figures started from chat (a `figure` part) are the same resources.

## 6. Push notifications

| Method | Path | Body |
|---|---|---|
| PUT | `/devices/{fcmToken}` | `{ "platform": "android", "appVersion": "0.2.0" }` |
| DELETE | `/devices/{fcmToken}` | |

Data payload: `{ "type": "figure.finished", "figureId": "fig_…", "status": "succeeded" }`. The app shows the notification and opens the figure when tapped.

## 7. OpenUI (interactive components)

Replies can include interactive UI written in OpenUI Lang (github.com/thesysdev/openui). The model may only use the components of the VisualLearn library:

| Component | What it does |
|---|---|
| `Flashcards` | A deck of cards; tap to flip, mark "knew it" or "again". |
| `MultipleChoice` | One question with instant feedback and an explanation. |
| `Quiz` | Several questions, a score, and "review mistakes" (sends a `uiAction`). |
| `StepExplainer` | Steps of an explanation, one at a time. |
| `Figure` | A reference to a figure job; the app shows its card. |
| Built-ins | Selected OpenUI components: Steps, Tabs, Accordion, Callout, CodeBlock, Table, Charts. |

The library is one versioned package shared by the server (which builds the model's system prompt from it with `@openuidev/lang-core`) and the app (whose bundled renderer registers the same components). The app sends `X-UI-Library-Version`; the server only emits programs the app can render, or answers `426` if the app is too old.

Interactions such as answering a quiz run entirely in the app. Only `@ToAssistant` actions reach the server, as a `uiAction` on the next message.

## 8. Gallery

The app reads the Anatomy community gallery directly from GitHub today. Optionally the server can proxy and cache it at `GET /gallery` with the same fields; not needed for v1.

## 9. Server architecture (proposal)

- API service (stateless) + Postgres (users, conversations, messages, figures) + object storage (pages, previews).
- Job queue; figure workers run each job in its own container: Node 22, Chromium, the Anatomy skill pinned to a commit, and the agent harness. Proven options: the Claude Agent SDK (Claude only) or OpenCode (`opencode run --format json`, multi-provider; close stdin or it waits for input).
- Workers report progress through the agent's `report_progress` tool and deliver pages through `finish`, the same contract as the on-device prototype (tag `on-device-poc`, `app/src/main/assets/linux/agent/agent.py`).
- Chat runs the model with the OpenUI system prompt and a `create_figure` tool that starts a figure job.

## 10. Open questions

1. Identity provider: Google only, or also email / Apple?
2. Quotas and pricing: figures per day per plan; what happens at the limit.
3. Agent harness for figures: Claude Agent SDK or OpenCode; which models users may pick, if any.
4. Data retention for conversations and figures; user deletion of their data.
5. Should chat work offline (read-only cache of conversations)?

## Fake server

Until the backend exists, debug builds of the app use an in-process fake of this API: it streams canned replies (including a sample OpenUI quiz) and runs simulated figure jobs that finish with a bundled example figure. It is selected in debug builds only and never ships in release.
