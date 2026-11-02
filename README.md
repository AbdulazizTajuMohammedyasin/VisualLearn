# VisualLearn

An Android chat app for learning. Ask a question, or attach a PDF of your notes, a chapter or a paper, and VisualLearn teaches it in whatever form fits each part: an explanation, a step-by-step walkthrough, flashcards, a quiz, or a working 3D figure for anything that is easier to understand by seeing it move: a pin tumbler lock you can turn, an engine you can run, a cache you can miss. Figures are made with [Anatomy](https://github.com/wheresryan22/anatomy) and shown in a sandboxed WebView.

The app is a thin client. All model calls and figure building happen on the VisualLearn server in [server/](server/); the phone signs in, streams chat, and renders what the server made.

## Status

| Area | State |
|---|---|
| Sign-in (Google, email code), account deletion | Done |
| Streaming chat with Markdown answers | Done |
| PDF study packs: attach a PDF and get explanations, steps, flashcards, a quiz and figures | Done |
| Figure jobs: progress, "Figure ready" notification, offline cache verified by sha256 | Done |
| Explore: Anatomy community gallery | Done |
| Settings, storage, licences | Done |
| Queue position for figures ("3rd in line") | Done |
| Offline read-only mode, kept up to date with `/sync` | Done |
| Interactive parts with [OpenUI](https://github.com/thesysdev/openui): quizzes, flashcards, step-by-step explainers | Done |
| Server ([server/](server/)): auth, chat streaming, figure queue with OpenCode workers, sync, admin API | Done; email delivery and push not wired yet |

## Requirements

- Android Studio (its bundled JBR is used as the JDK)
- Android SDK with platform 37
- A phone or emulator on Android 8.0 (API 26) or newer

Toolchain: Kotlin 2.4, AGP 9.5, Gradle 9.8, Jetpack Compose with Material 3. Versions live in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Build and run

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, if Gradle can't find a JDK, point `JAVA_HOME` at Android Studio's JBR, for example `C:\Program Files\Android\Android Studio\jbr`.

### The fake server

A debug build with no server URL talks to `FakeApi`, an in-process fake that follows the API contract. You can use the whole app without a backend:

- **Continue with Google** signs in with any account.
- **Continue with email** accepts any address; the code is `123456`.
- Ask "show me …", or turn on the Figure chip, to get a figure job. It queues, runs through 8 stages and finishes with a sample Anatomy figure.
- Ask "quiz me on …" to get an interactive part.

### Pointing at a real server

Pass Gradle properties, on the command line or in `~/.gradle/gradle.properties`:

| Property | Purpose |
|---|---|
| `visuallearn.apiBaseUrl` | Server base URL, e.g. `https://api.example.com`. Debug builds use the fake when it is empty; release builds always use it. |
| `visuallearn.googleWebClientId` | OAuth web client ID that the server checks Google ID tokens against. Without it, the Google button tells the user to use email instead. |

```sh
./gradlew assembleRelease -Pvisuallearn.apiBaseUrl=https://api.example.com -Pvisuallearn.googleWebClientId=1234-abc.apps.googleusercontent.com
```

### The interactive component library

`ui-library/` holds the OpenUI components the model may use (quizzes, flashcards, step-by-step explainers). The server and the app both use it. The app ships a prebuilt copy at `app/src/main/assets/openui/index.html`, so the Android build doesn't need Node. After changing the library:

```sh
cd ui-library
npm install
npm run check    # every example program must parse
npm run build    # rewrites the app's renderer page
npm run prompt   # writes dist/system-prompt.md for the server
```

Bump `LIBRARY_VERSION` in `src/library.tsx` and `UI_LIBRARY_VERSION` in `app/build.gradle.kts` together whenever a component's name or props change.

### Release signing

Release signing reads from environment variables, so no secrets go in the repo: `VL_KEYSTORE` (path), `VL_KEYSTORE_PASSWORD`, `VL_KEY_ALIAS`, `VL_KEY_PASSWORD`. Without them the release APK is unsigned.

## How it works

```
 Phone (app/)                              Server (server/)
 ┌──────────────────────────┐   HTTPS     ┌─────────────────────────────┐
 │ Compose UI               │  REST+SSE   │ API: auth, chat, figures,   │
 │ ChatRepository  ─────────┼────────────►│      sync, admin            │
 │ FiguresRepository        │             │ Scheduler (fair share)      │
 │ HttpApi / FakeApi        │◄────────────┤ OpenCode workers + Anatomy  │
 │ FigureView (WebView)     │  signed     │ Model routing set by admin  │
 └──────────────────────────┘  page URLs  └─────────────────────────────┘
```

- **Chat.** A reply is a list of parts: `text` (Markdown), `figure` (a figure job id) and `ui` (an OpenUI Lang program). They stream over Server-Sent Events, and the stream resumes with `Last-Event-ID` after a dropped connection.
- **Figures.** A figure is a server job. The app follows its progress, and when it finishes the app downloads the dark and light HTML pages. It checks each page's sha256 and keeps a copy on the phone, so a figure that has loaded once opens without a connection.
- **PDFs.** An attached PDF is uploaded to the chat, its text and pages read on the server. Models that read PDFs (Anthropic, OpenAI, Google) get the file itself, scans and diagrams included; others get the text. The model is told to build a study pack, choosing for each topic between an explanation, a step-by-step explainer, flashcards and a quiz, and starting a figure for up to two mechanisms.
- **Interactive parts.** `OpenUiView` loads the bundled renderer page into a WebView with no network. It sizes itself to the content, and answers survive scrolling, restarts and going offline. Follow-ups such as "Explain my mistakes" are sent as the next message's `uiAction`.
- **Rendering.** `FigureView` is a WebView with JavaScript on and every network request blocked. A figure can only use what is inside its own HTML file.
- **Offline.** Chats and figure records are stored on the phone in the server's JSON format (`offline/`). `/sync` brings in changes and deletions made elsewhere whenever the app starts or the connection returns. Without a connection, saved chats and figures still open; asking new questions needs a connection. Signing out wipes the phone's copy.
- **Sessions.** Tokens are encrypted with an Android Keystore key and refreshed on a 401.

The full contract, including scheduling, sync, the admin API and retention, is in [docs/server-api.md](docs/server-api.md).

## Project layout

```
app/src/main/java/com/visuallearn/app/
  api/        VisualLearnApi interface, HttpApi client, models
  auth/       TokenStore (Keystore), GoogleSignIn (Credential Manager)
  chat/       ChatRepository: conversations and streaming
  figures/    FiguresRepository: jobs, downloads, page cache, notifications
  figure/     FigureView: sandboxed WebView
  offline/    OfflineStore, Connectivity, SyncManager
  openui/     OpenUiView: WebView host for interactive parts
  gallery/    Explore gallery from anatomy-community
  settings/   User preferences
  ui/         Compose screens
app/src/debug/    FakeApi, sample figures, debug Backend
app/src/release/  release Backend (always HttpApi)
ui-library/       OpenUI component library: app renderer and server prompt
server/           API server (Node 24, Fastify, SQLite); see server/README.md
harness/          LearnHarness fork (AGPL-3.0): the learning engine being adopted; see harness/UPSTREAM.md
docs/server-api.md
```

## History

An earlier version ran the whole agent on the phone, in a Debian proot environment with Python, Node and OpenCode. It worked, but it needed about 2 GB of storage and a 64-bit phone. The project then moved all heavy work to servers. That version is kept at the git tag `on-device-poc`.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `type(scope): description`, lowercase and in the imperative, one feature or milestone per commit.

## Licences

The learning engine in [harness/](harness/) is a fork of [LearnHarness](https://github.com/amosblomqvist/learnharness) under the GNU AGPL-3.0. The VisualLearn server is being built on it, so the server will be AGPL-3.0 too, and its users are entitled to its source. See [harness/UPSTREAM.md](harness/UPSTREAM.md).

Third-party notices are in [app/src/main/assets/licenses.md](app/src/main/assets/licenses.md) and shown in the app under Settings → Open-source licences. Anatomy is MIT. Gallery figures are MIT or CC-BY-4.0, and each one shows its author.
