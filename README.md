# OpenCode Android

A native Android client for [OpenCode](https://opencode.ai) — the open-source AI coding agent that runs Claude, GPT, and other LLMs as autonomous software engineers.

> Talk to your codebase from your phone. Review PRs on the go. Delegate tasks to AI agents while AFK.

## Features

- **Real-time streaming** — SSE-based token streaming with 120ms throttled rendering, zero-flicker architecture
- **Multi-agent routing** — `@oracle`, `@fixer`, `@explorer` — delegate to specialist agents directly from the input bar
- **Slash commands** — `/review`, `/fix`, `/find` with autocomplete from server-side skill discovery
- **Tool call visualization** — collapsible tool invocations with input/output inspection
- **Subagent navigation** — tap into child agent sessions, trace delegation chains
- **File & image attachments** — pick from gallery or files, inline base64 upload
- **Image viewer** — full-screen overlay with swipe-to-dismiss
- **Markdown rendering** — full CommonMark support for assistant messages (headings, code blocks, lists, tables)
- **Custom design system** — "Plain" aesthetic with Superellipse shapes, Hanken Grotesk + JetBrains Mono typography, 4 accent colors
- **Dark/Light themes** — instant toggle, persisted across restarts
- **Foreground service** — optional local OpenCode binary execution with process lifecycle management
- **Edge-to-edge** — full immersive UI with proper IME/keyboard handling

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                         │
│  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌──────┐ │
│  │ Sessions│  │   Chat   │  │  Setup  │  │Sett. │ │
│  └────┬────┘  └────┬─────┘  └─────────┘  └──────┘ │
│       │             │                               │
│       │      ┌──────┴──────┐                        │
│       │      │ LazyColumn  │  ← reversed layout     │
│       │      │  messages[] │    streaming as item    │
│       │      │  streaming  │    key-based isolation  │
│       │      └──────┬──────┘                        │
├───────┼─────────────┼───────────────────────────────┤
│  Data Layer         │                               │
│  ┌──────────────────┴────────────────────────┐      │
│  │           OpenCodeApi (Ktor)              │      │
│  │  ┌─────────┐  ┌───────────┐  ┌────────┐  │      │
│  │  │REST HTTP│  │SSE events │  │Long-poll│  │      │
│  │  │(OkHttp) │  │(URLConn)  │  │(OkHttp) │  │      │
│  │  └─────────┘  └───────────┘  └────────┘  │      │
│  └───────────────────────────────────────────┘      │
│  ┌────────────────┐  ┌─────────────────────┐        │
│  │PreferencesRepo │  │ AppearanceRepo      │        │
│  │  (DataStore)   │  │  (DataStore)        │        │
│  └────────────────┘  └─────────────────────┘        │
├─────────────────────────────────────────────────────┤
│  Service Layer                                      │
│  ┌─────────────────────────────────────────┐        │
│  │  OpenCodeService (Foreground)           │        │
│  │  └── ProcessManager                     │        │
│  │       └── native binary (aarch64)       │        │
│  └─────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────┘
         │
         ▼
   OpenCode Server (local or remote)
   HTTP REST + SSE on configurable host:port
```

## Streaming Architecture

The streaming pipeline is designed to eliminate UI flicker:

```
SSE delta events
     │
     ▼
sseBuffer += delta          ← raw accumulation (no state write)
     │
     ▼ (every 120ms)
streamingText = sseBuffer   ← throttled state flush
     │
     ▼
LazyColumn item(key="streaming")  ← only this item recomposes
     │                               other messages untouched
     ▼
Plain Text() rendering      ← no markdown parse during stream
     │
     ▼ (on completion)
Incremental message sync    ← remove local msgs, add server msgs
     │                         no clear/rebuild flash
     ▼
MarkdownText() in MessageBubble  ← full render only once, final
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation (iOS-style transitions) |
| Networking | Ktor Client (OkHttp engine) + raw HttpURLConnection (SSE) |
| Serialization | kotlinx.serialization |
| Persistence | DataStore Preferences |
| Typography | Hanken Grotesk (variable) + JetBrains Mono (variable) |
| Markdown | multiplatform-markdown-renderer-m3 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 |
| Language | Kotlin 100% |

## Getting Started

### Connect to a remote OpenCode server

```bash
# On your machine:
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 0.0.0.0 --port 4096

# Or via USB (adb reverse):
adb reverse tcp:4096 tcp:4096
```

Then in the app, enter:
- **Server URL**: your machine's IP (or `127.0.0.1` via adb reverse)
- **Port**: `4096`

### Build from source

```bash
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires:
- Android Studio Ladybug+ or JDK 17
- Android SDK 36

## Project Structure

```
opencode-android/
├── android-app/
│   ├── app/src/main/java/com/opencode/android/
│   │   ├── MainActivity.kt          # Entry point, theme setup
│   │   ├── OpenCodeApp.kt           # Application class, notification channel
│   │   ├── OpenCodeNavHost.kt       # Navigation graph with iOS transitions
│   │   ├── data/
│   │   │   ├── api/OpenCodeApi.kt   # HTTP client, SSE, all server communication
│   │   │   ├── model/Models.kt      # Serializable data classes
│   │   │   └── repository/          # DataStore persistence
│   │   ├── service/
│   │   │   ├── OpenCodeService.kt   # Foreground service for local binary
│   │   │   └── ProcessManager.kt    # Native process lifecycle
│   │   └── ui/
│   │       ├── screen/              # Full-page composables
│   │       │   ├── ChatScreen.kt    # Main chat (SSE, polling, send, agents)
│   │       │   ├── SessionsScreen.kt
│   │       │   ├── SetupScreen.kt
│   │       │   └── SettingsScreen.kt
│   │       ├── component/           # Reusable UI pieces
│   │       │   ├── ChatComponents.kt  # MessageBubble, StreamingBubble
│   │       │   ├── ChatBits.kt        # ThinkingBlock, ToolCallRow
│   │       │   ├── Inputs.kt          # UnderlineField, OcButton
│   │       │   ├── LiveBits.kt        # BlinkingCursor, OnlineDot
│   │       │   ├── MarkdownText.kt    # Markdown renderer wrapper
│   │       │   └── Primitives.kt      # pressable, Hairline, MonoLabel
│   │       └── theme/
│   │           ├── Color.kt           # OcColors, OcTheme, accent system
│   │           ├── Shape.kt           # SuperellipseShape (squircle)
│   │           └── Type.kt            # Typography scale
│   └── app/src/main/res/
│       └── font/                      # Variable fonts (Hanken, JetBrains Mono)
└── build/                             # Native binary build scripts
```

## Credits

- [OpenCode](https://opencode.ai) — the AI coding agent this app connects to
- [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux) — aarch64 binary build reference
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) — Compose markdown

## License

MIT
