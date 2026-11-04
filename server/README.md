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

## Container

`server/Dockerfile` builds an image with Node 24, Chromium, OpenCode and Anatomy pinned to a commit. Build it from the repository root:

```sh
docker build -f server/Dockerfile -t visuallearn-server .
docker run -p 8080:8080 -v visuallearn-data:/data \
  -e VL_SIGNING_SECRET=… -e VL_PUBLIC_URL=https://api.example.com -e VL_SMTP_URL=… visuallearn-server
```

The image hasn't been built yet: no Docker on the development machine. Expect to adjust it on the first build.

## Tests

```sh
npm test         # node:test, in-memory database
npm run typecheck
VL_OPENCODE_BIN=$(which opencode) npm run test:opencode   # the real OpenCode against a scripted mock model
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
| `VL_CHROME_BIN` | `CHROME_PATH` | Chrome or Chromium that draws each finished figure's preview image. Empty: no previews. |
| `VL_CHROME_NO_SANDBOX` | empty | `1` runs Chrome without its sandbox, which most containers need. The image sets it. |
| `VL_HARNESS_DIR` | empty | The `harness/` folder (LearnHarness). Turns on lessons at `/v1/learn`. It must be built first; see "Lessons" below. |
| `VL_HARNESS_DEV` | empty | `1` runs `next dev` instead of the build. `next dev` can't run twice in one folder, so only one learner at a time. For development. |
| `VL_LEARN_MAX_INSTANCES` | `20` | Learners whose LearnHarness process may run at once. When full, the longest-idle one is stopped; if none is idle the request gets `503 lessons_busy`. |
| `VL_LEARN_IDLE_MIN` | `15` | A learner's process stops after this many minutes without requests. |
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

## Lessons (LearnHarness)

Lessons (probing, a plan, teaching with checks, practice and the knowledge graph) come from the LearnHarness fork in `harness/`. LearnHarness serves one person per process, so this server gives each learner their own:

1. A request to `/v1/learn/api/...` from a signed-in learner starts that learner's process if it isn't running: `harness/.next/standalone/server.js` on a free `127.0.0.1` port.
2. The process gets its own folders under `data/learn/<user id>/`: `data/` (lessons, graph, settings), `files/` (the only folder its file tools may read, via `LH_FILES_ROOT`), `home/`, and an empty `auth.json`. Its environment holds only host basics, those paths, and the provider keys for the admin's lesson models. Nothing else of this server's environment is passed on.
3. Its `settings.json` gets the admin's lesson models (`learn` in the routing) at every start. Changing the routing restarts each process once it's idle.
4. Each process is started with a random per-launch secret (LearnHarness's `LH_DESKTOP_API_SECRET`). The server adds it to forwarded requests, and the process refuses any request without it, so nothing else on the machine can reach a learner's lessons.
5. Only learner routes are forwarded: lessons, practice, the knowledge graph and its imports, and background work. Keys, providers, subscriptions, Claude Code, prompts and settings changes answer `403`. New messages and imports count against `VL_RATE_WORK_PER_MIN`.
6. Deleting an account stops its process and deletes its folder.
7. When figures are on, each process also gets `VL_FIGURE_URL` (this server's `/v1/internal`, on `127.0.0.1`) and a learner token. That gives the teacher a `figure` tool: it asks this server for a 3D Anatomy figure for that learner, which joins the same fair-share queue, appears in the learner's figures, and comes back into the lesson with its figure id. The internal routes accept only requests from this machine that carry a valid learner token, and only for that learner's figures.

Build LearnHarness once, and again after changing `harness/`. On Windows, stop this server first: running lesson processes keep the build folder locked.

```sh
cd harness
ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm ci
npx next build && node scripts/build-server.mjs
```

Set the lesson models along with the other routes. `strong` runs the prober and teacher, `cheap` the background workers (research, demos, graph extraction), and `vision` the PDF reader and figure checks. They can be on Google, Anthropic or OpenAI providers, or on one OpenAI-compatible endpoint, such as a university's vLLM, SGLang or Ollama server. Put several local models behind one gateway, because LearnHarness takes a single custom endpoint.

A local endpoint must have **tool calling** switched on: every lesson request carries tools (11 on the first turn). Because such servers rarely say what their models can do, give each model's limits when registering the provider. Otherwise LearnHarness assumes an 8,192-token context and a 4,096-token reply, which is too small for lessons:

```sh
curl -X PUT $API/v1/admin/providers/uni -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"   -d '{"kind":"local","npm":"@ai-sdk/openai-compatible","baseUrl":"https://gpu.uni.example/v1","apiKey":"…",
       "models":["big-model","vision-model"],
       "modelInfo":{"big-model":{"context":131072,"maxTokens":16384,"reasoning":true},"vision-model":{"context":65536,"image":true}}}'
```

The server writes the endpoint into each learner's LearnHarness settings with a fresh authorization binding, and passes its key only in the process environment (`CUSTOM_OPENAI_API_KEY`), never on disk.

```sh
curl -X PUT $API/v1/admin/routing -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"chat":{"model":"google/gemini-3.8-flash"},"figures":{"model":"google/gemini-3.8-flash"},
       "learn":{"strong":"google/gemini-3.8-flash","cheap":"google/gemini-3.6-flash","vision":"google/gemini-2.5-flash"}}'
```

## How a figure job runs

1. The job waits in a queue ordered round-robin across users: the least recently served person goes next.
2. A worker creates a work folder `data/jobs/<id>/` and runs OpenCode with its home in `data/opencode/`, isolated from the machine's own OpenCode setup. That home holds the generated `opencode.json`, the Anatomy skill, and two custom tools: `report_progress` streams stages to the app and `finish` delivers the dark and light pages. OpenCode installs the tools' packages there once.
3. Pages are stored by sha256 under `data/pages/` and served at `/v1/pages/<sha>.html` through links signed for one hour.
4. Headless Chrome screenshots the dark page, with no network, into `data/pages/<sha>.png`; it is the figure's `previewUrl`. Figures finished without a preview get one at the next start.
5. A retry resumes the saved OpenCode session. Jobs cut off by a restart go back to the queue.

## Before production

- **Email:** set `VL_SMTP_URL` to a transactional email service such as SES, Postmark or Resend. Without it, sign-in codes are only logged.
- **Push:** "figure ready" notifications through Firebase Cloud Messaging aren't built. The app finds out from the live stream and sync.
- **Scale:** SQLite and the in-process queue suit one server. Moving past that means Postgres and a shared queue (spec section 11).
- **Isolation:** each figure job should run in its own container, not just its own folder.
- **Operations:** set `VL_SIGNING_SECRET`, put HTTPS in front, and back up `VL_DATA_DIR`.
