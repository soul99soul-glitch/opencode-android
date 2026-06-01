# opencode-android

opencode-android is an experimental native Android client for [OpenCode](https://opencode.ai). It explores what it would take to bring a coding-agent workflow, normally tied to a laptop terminal or browser, onto a phone or tablet.

The project is not trying to be a generic chatbot. Its focus is the mobile developer experience around OpenCode: connecting to an OpenCode server, streaming coding-agent output, managing sessions and messages, and experimenting with Android-side runtime orchestration through Termux or local binaries.

This repository is independent from upstream OpenCode. It is an Android client and integration experiment that speaks to OpenCode's HTTP/SSE API; it is not an official OpenCode app.

## Why This Matters

AI coding agents are still mostly desktop tools. opencode-android asks a practical question: can a developer inspect, delegate, and continue coding-agent work from a mobile device without turning the phone into a weak copy of a desktop IDE?

That makes the project useful as open-source infrastructure and research material:

- It gives Android developers a concrete Compose/Kotlin client for a real coding-agent server.
- It documents the rough edges of REST, SSE streaming, process management, and mobile UX for agent tools.
- It helps explore non-desktop access to AI development workflows, including Termux and Android-native runtime paths.
- It is intentionally small enough to study, fork, and improve.

The maintainer also explores other Android AI-agent projects, including more personal mobile agent work. opencode-android has a narrower role: a mobile OpenCode client and coding-agent workflow experiment.

## Project Highlights

- **Native Android client for OpenCode** - Jetpack Compose UI for sessions, messages, setup, settings, agent selection, and model selection.
- **REST and SSE integration** - Ktor/OkHttp REST calls plus raw `HttpURLConnection` SSE streaming against an OpenCode server.
- **Streaming coding-agent UI** - incremental assistant output, throttled rendering, message reconciliation, abort support, and markdown rendering after completion.
- **Session and message state management** - session list, session creation/deletion, message fetch, optimistic local messages, and server sync.
- **Mobile-first development workflow experiment** - touch-first chat, file/image attachment preparation, slash-command autocomplete from OpenCode skills, and subagent navigation hooks.
- **Local server/runtime orchestration research** - Android foreground service and process manager code for launching an `opencode serve` process when a compatible binary is available.
- **Termux and Android runtime bridge** - the `build/` tree contains packaging and research work around `opencode-termux`, Android/Bionic constraints, glibc-wrapped OpenCode binaries, and package generation.
- **Open-source Android + AI tooling value** - a reproducible place to study how coding-agent clients behave outside the usual desktop environment.

## Current Status and Scope

This is an early development project. The app is useful for experimentation, but it should not be presented as a polished or stable public release.

Implemented in the Android app today:

- Configure an OpenCode server by host/URL, port, optional password, and directory.
- Check `/global/health` before entering the app.
- List, create, open, enrich, and delete OpenCode sessions.
- Fetch and send OpenCode messages.
- Stream server events from `/event` with session filtering.
- Render assistant output while streaming, then render final markdown messages.
- Fetch available agents, skills, and configured providers/models.
- Select a default agent and model.
- Prepare image/file attachments as prompt parts.
- Show tool-call-oriented message parts and subagent navigation affordances.
- Persist connection, appearance, agent, and model preferences with DataStore.
- Provide light/dark theme and accent settings.

Still experimental or in progress:

- Bundling a working OpenCode binary inside the APK.
- Starting and supervising a local OpenCode runtime entirely from the app.
- Production-quality Termux integration and handoff flows.
- Hardening SSE reconnect behavior across network changes and Android background limits.
- Release packaging, signing, update distribution, and end-user onboarding.
- Comprehensive automated tests.

The `OpenCodeService` and `ProcessManager` code currently look for an executable in these locations:

- `context.applicationInfo.nativeLibraryDir/libopencode.so`
- `context.filesDir/bin/opencode`
- `context.filesDir/opencode`

Those paths are part of the local-runtime experiment. The repository currently does not include a prebuilt APK-ready OpenCode binary under `android-app/app/src/main/jniLibs`, so the most reliable path is to run OpenCode separately and connect the app to it.

## Relationship to OpenCode, Termux, and opencode-termux

OpenCode is the coding agent server/runtime. opencode-android is a client that talks to it.

Typical development setup:

1. Run `opencode serve` on a development machine, in Termux, or in another reachable environment.
2. Point the Android app at that server.
3. Use the app as a mobile session/message UI for the coding agent.

Termux matters because it is one of the most realistic ways to run developer tooling directly on Android today. The `build/` directory contains related packaging and runtime research, including references to [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux). That work is complementary to the Android app: it helps answer how OpenCode can run on Android-like environments, while the app explores how a mobile client should feel.

## Build From Source

Requirements:

- Android Studio with Android Gradle Plugin support for this project.
- JDK 17.
- Android SDK 36.
- An OpenCode server to connect to for runtime testing.

Build the debug APK:

```bash
cd android-app
./gradlew assembleDebug
```

Install it on a connected device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Useful verification command:

```bash
./gradlew :app:assembleDebug
```

## Run With an OpenCode Server

### Option A: Server on your development machine

Start OpenCode so it listens on the network:

```bash
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 0.0.0.0 --port 4096
```

Then enter the machine's LAN IP and port `4096` in the app.

If you prefer not to expose the port on the LAN, use USB reverse port forwarding:

```bash
adb reverse tcp:4096 tcp:4096
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 127.0.0.1 --port 4096
```

Then use `127.0.0.1` and port `4096` in the app.

### Option B: Server in Termux

Install or build an OpenCode runtime for Termux, then run:

```bash
opencode serve --hostname 127.0.0.1 --port 4096
```

The Android app can connect to `127.0.0.1:4096` when the server is reachable from the same Android environment. Depending on how OpenCode is installed and isolated, you may need to adjust host, port, or Android networking setup.

The Termux packaging research in this repository lives under `build/`; see [build/README.md](build/README.md) and [build/docs/native-android-research.md](build/docs/native-android-research.md) for the runtime side.

### Passwords and directories

The app supports:

- Optional Basic auth using the configured OpenCode password.
- `x-opencode-directory` for selecting the working directory.
- Full server URLs such as `http://192.168.1.10:4096`, or separate host/port fields.

## Repository Layout

```text
opencode-android/
├── README.md
├── android-app/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── app/
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/opencode/android/
│           │   ├── data/api/OpenCodeApi.kt
│           │   ├── data/model/Models.kt
│           │   ├── data/repository/
│           │   ├── service/OpenCodeService.kt
│           │   ├── service/ProcessManager.kt
│           │   └── ui/
│           └── res/
└── build/
    ├── README.md
    ├── docs/
    ├── scripts/
    ├── patches/
    └── packaging/
```

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Navigation | Compose Navigation |
| Networking | Ktor Client, OkHttp, raw `HttpURLConnection` for SSE |
| Serialization | kotlinx.serialization |
| Persistence | AndroidX DataStore Preferences |
| Markdown | `multiplatform-markdown-renderer-m3` |
| Service experiment | Android foreground service + `ProcessBuilder` |
| Minimum SDK | 26 |
| Target/compile SDK | 36 |

## Contributing

Contributions are welcome, especially around:

- OpenCode API compatibility.
- SSE streaming reliability.
- Android process/runtime integration.
- Termux handoff and documentation.
- Mobile UX for coding-agent sessions.
- Tests and small reproducible examples.

Please keep changes honest about project maturity. A good contribution should make the mobile OpenCode workflow more understandable, more reliable, or easier to reproduce.

## License

MIT. See [build/LICENSE](build/LICENSE).

## Credits

- [OpenCode](https://opencode.ai) for the coding-agent runtime this app connects to.
- [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux) for Android/Termux runtime packaging reference work.
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) for Compose markdown rendering.
