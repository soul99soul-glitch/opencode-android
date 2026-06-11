# opencode-android

[中文说明](README.zh-CN.md)

A native Android client for [OpenCode](https://opencode.ai), built with Jetpack Compose.

The app connects to a running OpenCode server so you can browse sessions, send messages, and follow streamed responses from a phone or tablet. It is a mobile interface for OpenCode, not a general-purpose chat app.

> [!IMPORTANT]
> This project is still early. There is no stable release, and the APK does not include a working OpenCode binary. The simplest setup is to run `opencode serve` on a computer or in Termux, then connect the app to it.

This is an independent project and is not affiliated with the OpenCode maintainers.

## What works

- Connect to an OpenCode server using a full URL or separate host and port fields
- Use password-based Basic Auth and set a working directory with `x-opencode-directory`
- Create, open, and delete sessions
- Send and fetch messages
- Receive server events over SSE
- Show text while a response is streaming, then render the completed message as Markdown
- Choose agents and models reported by the server
- Build slash-command suggestions from OpenCode skills
- Prepare image and file attachments
- Display tool calls and links to child sessions
- Save connection, appearance, agent, and model preferences
- Switch between light and dark themes and choose an accent color

## What is not ready

- Shipping an OpenCode runtime inside the APK
- Starting and managing a local OpenCode server entirely from the app
- A dependable Termux handoff flow
- Reliable SSE reconnection across network changes and Android background limits
- Signed releases and automatic updates
- Broad automated test coverage

`OpenCodeService` and `ProcessManager` already try to find an executable in these locations:

```text
<nativeLibraryDir>/libopencode.so
<filesDir>/bin/opencode
<filesDir>/opencode
```

That code is experimental. This repository does not contain an OpenCode binary that can be bundled directly into the APK.

## Build from source

You need JDK 17, Android SDK 36, and an Android Studio version that supports the Android Gradle Plugin used by the project.

```bash
cd android-app
./gradlew assembleDebug
```

The APK is written to:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device or emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect to an OpenCode server

### Run the server on your computer

Start OpenCode on an address reachable from your Android device:

```bash
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 0.0.0.0 --port 4096
```

Enter the computer's local network address and port `4096` in the app.

For a device connected over USB, ADB reverse port forwarding avoids exposing the server to the local network:

```bash
adb reverse tcp:4096 tcp:4096
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 127.0.0.1 --port 4096
```

The app can then connect to `127.0.0.1:4096`.

### Run the server in Termux

Install or build an OpenCode runtime that works in Termux, then start the server:

```bash
opencode serve --hostname 127.0.0.1 --port 4096
```

The app can usually connect to `127.0.0.1:4096`. You may need a different address if OpenCode runs inside another compatibility or isolation layer.

The `build/` directory contains the repository's Termux and Android runtime packaging work. See [build/README.md](build/README.md) and [build/docs/native-android-research.md](build/docs/native-android-research.md). Part of that work references [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux).

## Repository layout

```text
opencode-android/
├── android-app/    # Android client
│   └── app/src/
│       ├── main/   # Compose UI, API client, data, and service code
│       └── test/   # Unit tests
└── build/          # Termux and Android runtime packaging experiments
```

The Android source lives under `android-app/app/src/main/java/com/opencode/android/`:

- `data/api/OpenCodeApi.kt`: OpenCode HTTP API and SSE client
- `data/model/Models.kt`: API data models
- `data/repository/`: DataStore-backed preferences
- `ui/`: Compose screens and components
- `service/`: experimental local runtime launcher

## Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Navigation | Navigation Compose |
| Networking | Ktor Client, OkHttp, `HttpURLConnection` |
| Serialization | kotlinx.serialization |
| Preferences | AndroidX DataStore |
| Markdown | multiplatform-markdown-renderer |
| Minimum Android version | API 26 |
| Compile and target version | API 36 |

## Contributing

The main gaps are OpenCode API compatibility, SSE reliability, the local runtime, Termux setup, and tests. When reporting a bug or sending a change, include the OpenCode version you tested and the steps needed to reproduce the behavior.

## License

[MIT](build/LICENSE)

## Credits

- [OpenCode](https://opencode.ai)
- [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
