# VisualLearn

An Android chat app for learning. Ask a question and get an answer. When an idea is easier to understand by seeing it, VisualLearn builds a working, interactive figure of it: a pin tumbler lock you can turn, a rate limiter you can flood, a cache you can miss. Figures are made with [Anatomy](https://github.com/wheresryan22/anatomy) and shown in a sandboxed WebView.

The app is a thin client. All model calls and figure building happen on VisualLearn's servers; the phone signs in, streams chat, and renders what the server made.

## Status

| Area | State |
|---|---|
| Sign-in (Google, email code), account deletion | Done |
| Streaming chat with Markdown answers | Done |
| Figure jobs: progress, "Figure ready" notification, offline cache verified by sha256 | Done |
| Explore: Anatomy community gallery | Done |
| Settings, storage, licences | Done |
| Queue position for figures ("3rd in line") | Next |
| Offline read-only mode with `/sync` | Next |
| Interactive parts (flashcards, quizzes) with [OpenUI](https://github.com/thesysdev/openui) | Placeholder card for now |
| Server | Not built yet. Debug builds use an in-app fake server |

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

### Release signing

Release signing reads from environment variables, so no secrets go in the repo: `VL_KEYSTORE` (path), `VL_KEYSTORE_PASSWORD`, `VL_KEY_ALIAS`, `VL_KEY_PASSWORD`. Without them the release APK is unsigned.

## How it works

```
 Phone (this repo)                         Server (planned)
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
- **Rendering.** `FigureView` is a WebView with JavaScript on and every network request blocked. A figure can only use what is inside its own HTML file.
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
  gallery/    Explore gallery from anatomy-community
  settings/   User preferences
  ui/         Compose screens
app/src/debug/    FakeApi, sample figures, debug Backend
app/src/release/  release Backend (always HttpApi)
docs/server-api.md
```

## History

An earlier version ran the whole agent on the phone, in a Debian proot environment with Python, Node and OpenCode. It worked, but it needed about 2 GB of storage and a 64-bit phone. The project then moved all heavy work to servers. That version is kept at the git tag `on-device-poc`.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `type(scope): description`, lowercase and in the imperative, one feature or milestone per commit.

## Licences

Third-party notices are in [app/src/main/assets/licenses.md](app/src/main/assets/licenses.md) and shown in the app under Settings → Open-source licences. Anatomy is MIT. Gallery figures are MIT or CC-BY-4.0, and each one shows its author.
