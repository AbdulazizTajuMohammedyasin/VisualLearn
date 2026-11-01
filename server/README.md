# VisualLearn server

The API the Android app talks to, as specified in [docs/server-api.md](../docs/server-api.md): sign-in, streaming chat with interactive OpenUI parts, figure jobs on a fair-share queue, offline sync, and an admin API for model routing and content.

Node 24 runs the TypeScript directly, so there is no build step. Data lives in SQLite (`node:sqlite`) and finished figure pages are files.

## Run it locally

```sh
cd ui-library && npm install && npm run prompt   # the chat model's OpenUI instructions
cd ../server && npm install
VL_DEV_AUTH=1 npm start
```

With `VL_DEV_AUTH=1` the email code `123456` works for any address and real codes are printed to the log. No model keys are needed. Until an admin sets routing, chat and figures use the built-in `dev` model, which gives canned replies and returns Anatomy's sample figure.

To point the app's debug build at it from the Android emulator:

```sh
VL_DEV_AUTH=1 VL_PUBLIC_URL=http://10.0.2.2:8080 npm start
./gradlew installDebug -Pvisuallearn.apiBaseUrl=http://10.0.2.2:8080/v1
```

Debug builds may use plain HTTP to `10.0.2.2` and `localhost`; release builds need HTTPS.

## Tests

```sh
npm test         # node:test, in-memory database
npm run typecheck
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT`, `HOST` | `8080`, `0.0.0.0` | Where to listen. |
| `VL_DATA_DIR` | `./data` | Database, figure pages, and per-job OpenCode folders. |
| `VL_PUBLIC_URL` | `http://localhost:PORT` | Base of the signed page links the app downloads. |
| `VL_SIGNING_SECRET` | none | Signs access tokens and page links. Required unless `VL_DEV_AUTH=1`. |
| `VL_DEV_AUTH` | off | Development only: accepts code `123456` and logs codes. |
| `VL_SMTP_URL` | empty | SMTP for sign-in codes, e.g. `smtps://user:pass@smtp.postmarkapp.com:465`; empty logs codes instead. |
| `VL_MAIL_FROM` | `VisualLearn <no-reply@visuallearn.app>` | Sender of sign-in emails. |
| `VL_GOOGLE_CLIENT_ID` | empty | OAuth web client ID for Sign in with Google; empty turns Google sign-in off. |
| `VL_ADMIN_EMAILS` | empty | Comma-separated emails that get the admin role on sign-in. |
| `VL_FIGURE_WORKERS` | `2` | Figure jobs running at once. |
| `VL_ANATOMY_DIR` | empty | A checkout of [Anatomy](https://github.com/wheresryan22/anatomy). Real figure models need it. |
| `VL_OPENCODE_BIN` | `opencode` | The OpenCode executable (`npm i -g opencode-ai`). |
| `VL_FIGURE_TIMEOUT_MIN` | `120` | A figure job is stopped after this long. |
| `VL_UI_PROMPT` | `../ui-library/dist/system-prompt.md` | The OpenUI prompt from `npm run prompt`. |
| `VL_MIN_UI_LIBRARY` | `1.0.0` | Apps reporting an older UI library get `426`. |
| `VL_RATE_AUTH_PER_MIN`, `VL_RATE_REQUESTS_PER_MIN`, `VL_RATE_WORK_PER_MIN` | `30`, `600`, `30` | Abuse limits: sign-in calls per IP, requests per user, and new messages or figures per user. |

## Choosing models

An admin (an account listed in `VL_ADMIN_EMAILS`) registers providers and routes work to them. Keys are write-only: responses show `••••`.

```sh
curl -X PUT $API/v1/admin/providers/anthropic -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"kind":"api","npm":"@ai-sdk/anthropic","apiKey":"sk-…","models":["claude-opus-5-5","claude-sonnet-5-5"]}'
curl -X PUT $API/v1/admin/routing -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"chat":{"model":"anthropic/claude-sonnet-5-5","fallback":null},"figures":{"model":"anthropic/claude-opus-5-5","fallback":null}}'
```

Local models use `"kind":"local","npm":"@ai-sdk/openai-compatible","baseUrl":"http://…/v1"` (vLLM, Ollama, LM Studio). Chat calls the routed model through the Vercel AI SDK. Each figure job runs `opencode run` with a config generated from the figure route. The API key is passed through the job's environment and never written to disk.

## How a figure job runs

1. The job waits in a queue ordered round-robin across users: the least recently served person goes next.
2. A worker creates `data/jobs/<id>/` holding its own OpenCode config (`XDG_CONFIG_HOME`), the Anatomy skill, and two custom tools. `report_progress` streams stages to the app and `finish` delivers the dark and light pages.
3. Pages are stored by sha256 under `data/pages/` and served at `/v1/pages/<sha>.html` through links signed for one hour.
4. A retry resumes the saved OpenCode session. Jobs cut off by a restart go back to the queue.

## Before production

- **Email:** set `VL_SMTP_URL` to a transactional email service such as SES, Postmark or Resend. Without it, sign-in codes are only logged.
- **Push:** "figure ready" notifications through Firebase Cloud Messaging aren't built. The app finds out from the live stream and sync.
- **Scale:** SQLite and the in-process queue suit one server. Moving past that means Postgres and a shared queue (spec section 11).
- **Isolation:** each figure job should run in its own container, not just its own folder.
- **Operations:** set `VL_SIGNING_SECRET`, put HTTPS in front, and back up `VL_DATA_DIR`.
