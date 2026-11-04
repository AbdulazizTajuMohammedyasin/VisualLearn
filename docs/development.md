# Developing VisualLearn

How to build the Android app, run it against a fake or real server, and work on the interactive component library. The server has its own guide in [server/README.md](../server/README.md).

## Requirements

- Android Studio (its bundled JBR is used as the JDK)
- Android SDK with platform 37
- A phone or emulator on Android 8.0 (API 26) or newer
- Node 24 for the server and `ui-library/`

Toolchain: Kotlin 2.4, AGP 9.5, Gradle 9.8, Jetpack Compose with Material 3. Versions live in [gradle/libs.versions.toml](../gradle/libs.versions.toml).

## Build and run the app

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, if Gradle can't find a JDK, point `JAVA_HOME` at Android Studio's JBR, for example `C:\Program Files\Android\Android Studio\jbr`.

Checks:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

### The fake server

A debug build with no server URL talks to `FakeApi`, an in-process fake that follows the API contract, so the app works without a backend:

- **Continue with Google** signs in with any account.
- **Continue with email** accepts any address; the code is `123456`.
- Ask "show me …", or turn on the Figure chip, to get a figure job. It queues, runs through 8 stages and finishes with a sample Anatomy figure.
- Ask "quiz me on …" to get an interactive part.

Lessons, practice and What you know need the real server, because they run on LearnHarness.

### Pointing at a real server

Pass Gradle properties, on the command line or in `~/.gradle/gradle.properties`:

| Property | Purpose |
|---|---|
| `visuallearn.apiBaseUrl` | Server base URL, e.g. `https://api.example.com/v1`. Debug builds use the fake when it is empty; release builds always use it. |
| `visuallearn.googleWebClientId` | OAuth web client ID that the server checks Google ID tokens against. Without it, the Google button tells the user to use email instead. |

For a server on your computer and the Android emulator:

```sh
adb reverse tcp:8080 tcp:8080
./gradlew installDebug -Pvisuallearn.apiBaseUrl=http://localhost:8080/v1
```

Debug builds may use plain HTTP to `localhost` and `10.0.2.2`; release builds need HTTPS.

```sh
./gradlew assembleRelease -Pvisuallearn.apiBaseUrl=https://api.example.com/v1 -Pvisuallearn.googleWebClientId=1234-abc.apps.googleusercontent.com
```

### Release signing

Release signing reads environment variables, so no secrets go in the repository: `VL_KEYSTORE` (path), `VL_KEYSTORE_PASSWORD`, `VL_KEY_ALIAS`, `VL_KEY_PASSWORD`. Without them the release APK is unsigned.

## The interactive component library

`ui-library/` holds the [OpenUI](https://github.com/thesysdev/openui) components the chat model may use: quizzes, flashcards and step-by-step explainers. The server and the app both use it. The app ships a prebuilt copy at `app/src/main/assets/openui/index.html`, so the Android build doesn't need Node. After changing the library:

```sh
cd ui-library
npm install
npm run check    # every example program must parse
npm run build    # rewrites the app's renderer page
npm run prompt   # writes dist/system-prompt.md for the server
```

Bump `LIBRARY_VERSION` in `src/library.tsx` and `UI_LIBRARY_VERSION` in `app/build.gradle.kts` together whenever a component's name or props change.

## How the app works

- **Chat.** A reply is a list of parts: `text` (Markdown), `figure` (a figure job id) and `ui` (an OpenUI Lang program). They stream over Server-Sent Events, and the stream resumes with `Last-Event-ID` after a dropped connection.
- **Figures.** A figure is a server job. The app follows its progress, and when it finishes the app downloads the dark and light HTML pages. It checks each page's sha256 and keeps a copy on the phone, so a figure that has loaded once opens without a connection.
- **PDFs in chat.** An attached PDF is uploaded to the chat and its text and pages are read on the server. Models that read PDFs (Anthropic, OpenAI, Google) get the file itself, scans and diagrams included; others get the text. The model builds a study pack, choosing for each topic between an explanation, a step-by-step explainer, flashcards and a quiz, and starts a figure for up to two mechanisms.
- **Interactive parts.** `OpenUiView` loads the bundled renderer page into a WebView with no network. It sizes itself to the content, and answers survive scrolling, restarts and going offline.
- **Rendering.** `FigureView` is a WebView with JavaScript on and every network request blocked. A figure can only use what is inside its own HTML file.
- **Lessons.** The app reads LearnHarness's own records: `learn/LessonJson` turns a lesson's messages into what the screen shows, and `learn/Answers.kt` builds answers the way LearnHarness's web client does, grading included. An open lesson is followed live over its event stream. Background work (a PDF being read, a figure, a demo) is checked every few seconds, because the teacher picks up its results on its own.
- **What you know and practice.** The knowledge graph (`GET /api/graph`) is shown as topics of ideas, each with what it follows from and the lessons it came up in. Notes go in through LearnHarness's import. **Practise** starts a practice session seeded with a topic's ideas.
- **Offline.** Chats, figure records and lessons are stored on the phone in the server's JSON format (`offline/`). `/sync` brings in changes made elsewhere whenever the app starts or the connection returns. Signing out wipes the phone's copy.
- **Sessions.** Tokens are encrypted with an Android Keystore key and refreshed on a 401.

The full API contract, including scheduling, sync, the admin API and retention, is in [server-api.md](server-api.md).

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `type(scope): description`, lowercase and in the imperative, one feature or milestone per commit.
