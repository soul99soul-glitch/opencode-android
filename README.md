# OpenCode Android

A native Android client for [OpenCode](https://opencode.ai) — the open source AI coding agent.

## Architecture

- **Native UI**: Jetpack Compose + Material 3 (GitHub Dark theme)
- **Backend**: OpenCode server runs locally via background service
- **Protocol**: HTTP REST + SSE for real-time streaming
- **Binary**: Based on [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux) (MIT)

## Setup

1. Open `android-app/` in Android Studio
2. Run `gradle wrapper` to generate gradlew
3. Build & run on device

## Project Structure

```
build/           ← Binary build scripts (from Hope2333)
android-app/     ← Native Android application
  └── app/
      └── src/main/java/com/opencode/android/
          ├── data/        ← API client, models, preferences
          ├── service/     ← Background process manager
          └── ui/          ← Compose screens & components
```

## License

MIT
