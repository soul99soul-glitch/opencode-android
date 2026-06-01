# opencode-android

[English README](README.md)

opencode-android 是一个面向 [OpenCode](https://opencode.ai) 的原生 Android 客户端实验。它探索的是：通常运行在电脑终端或浏览器里的 coding agent 工作流，能不能自然地来到手机或平板上。

这个项目不是普通聊天 App。它关注的是围绕 OpenCode 的移动端开发体验：连接 OpenCode 服务、流式展示 coding agent 输出、管理会话和消息，以及通过 Termux 或本地二进制探索 Android 侧的运行时编排。

项目亮点可以先概括为：

- 用 Jetpack Compose 构建的 OpenCode 原生 Android 会话和消息客户端。
- 面向 coding-agent 交互的 REST + SSE streaming 集成。
- 面向 Android、Termux 和打包二进制的早期本地 server/runtime 编排探索。
- 面向移动设备的开发者工具 UX 实验，用来查看、委派和继续 agent 工作。

本仓库独立于 OpenCode 上游。它是一个 Android 客户端和集成实验，通过 OpenCode 的 HTTP/SSE API 工作；除非上游另有说明，它不是官方 OpenCode App。

## 为什么做这个项目

AI coding agent 目前仍然主要是桌面工具。opencode-android 想回答一个很具体的问题：开发者能不能在移动设备上查看、委派和继续 coding agent 的工作，而不是把手机硬拗成一个弱化版桌面 IDE？

这让它有几类开源价值：

- 给 Android 开发者一个真实连接 coding-agent server 的 Compose/Kotlin 客户端参考。
- 记录 REST、SSE streaming、进程管理和移动端 agent UX 中会遇到的实际边界。
- 探索 Termux、Android 原生运行环境等非桌面开发者工具路径。
- 项目体量相对可读，适合学习、复现、fork 和贡献。

维护者也在探索其他 Android AI agent 项目，包括更偏个人移动端 Agent 工作区的方向。opencode-android 的定位更窄：移动端 OpenCode 客户端，以及 mobile coding-agent workflow 实验。

## 项目亮点

- **OpenCode 原生 Android 客户端** - 使用 Jetpack Compose 构建会话、消息、初始化、设置、Agent 选择和模型选择等界面。
- **REST 与 SSE 集成** - 通过 Ktor/OkHttp 调用 REST API，并使用原始 `HttpURLConnection` 连接 OpenCode 的 SSE 事件流。
- **面向 coding agent 的流式 UI** - 支持增量 assistant 输出、节流渲染、消息对账、abort，以及完成后的 Markdown 渲染。
- **会话和消息状态管理** - 支持会话列表、创建/删除会话、消息拉取、乐观本地消息和服务端同步。
- **移动优先的开发工作流实验** - 触摸优先的聊天体验、文件/图片附件准备、来自 OpenCode skills 的 slash-command 自动补全，以及 subagent 导航钩子。
- **本地服务/运行时编排研究** - 已有 Android 前台服务和进程管理代码，用于在存在兼容二进制时启动 `opencode serve`。
- **Termux 与 Android runtime 桥接** - `build/` 目录包含围绕 `opencode-termux`、Android/Bionic 限制、glibc 包装的 OpenCode 二进制和包构建的研究。
- **Android + AI tooling 的开源价值** - 提供一个可复现的场所，用来观察 coding-agent client 在非桌面环境中的形态。

## 当前状态和范围

这是一个早期开发项目。它已经可以用于实验，但不应该被描述成成熟、稳定的公开发行版。

Android App 目前已经实现：

- 配置 OpenCode server 的 host/URL、port、可选 password 和 directory。
- 进入 App 前检查 `/global/health`。
- 列出、创建、打开、补全展示和删除 OpenCode sessions。
- 拉取和发送 OpenCode messages。
- 从 `/event` 读取 server events，并按 session 过滤。
- streaming 期间渲染 assistant 输出，完成后渲染最终 Markdown 消息。
- 拉取可用 agents、skills 和已配置 providers/models。
- 选择默认 agent 和 model。
- 将图片/文件附件准备为 prompt parts。
- 展示 tool-call 风格的 message parts，并保留 subagent navigation 入口。
- 使用 DataStore 持久化连接、外观、agent 和 model 偏好。
- 提供 light/dark theme 和 accent 设置。

仍在实验或开发中：

- 将可工作的 OpenCode binary 打包进 APK。
- 完全从 App 内启动并监管本地 OpenCode runtime。
- 更完整的 Termux 集成与交接流程。
- 在网络变化和 Android 后台限制下加固 SSE 重连行为。
- Release packaging、签名、更新分发和面向最终用户的 onboarding。
- 完整自动化测试。

当前 `OpenCodeService` 和 `ProcessManager` 会在这些位置查找可执行文件：

- `context.applicationInfo.nativeLibraryDir/libopencode.so`
- `context.filesDir/bin/opencode`
- `context.filesDir/opencode`

这些路径属于本地 runtime 实验的一部分。仓库目前没有在 `android-app/app/src/main/jniLibs` 下包含可直接打包进 APK 的 OpenCode 预构建二进制，所以最可靠的使用方式仍然是单独运行 OpenCode server，然后让 App 连接它。

## 与 OpenCode、Termux 和 opencode-termux 的关系

OpenCode 是 coding agent server/runtime。opencode-android 是连接它的客户端。

典型开发设置：

1. 在开发机、Termux 或其他 Android 可访问的环境中运行 `opencode serve`。
2. 在 Android App 里填入该 server 地址。
3. 把 App 作为移动端 session/message UI 使用。

Termux 很重要，因为它是今天在 Android 上直接运行开发者工具的现实路径之一。本仓库的 `build/` 目录包含相关 packaging 和 runtime 研究，也引用了 [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)。这部分工作和 Android App 互补：前者回答 OpenCode 如何在 Android-like 环境中运行，后者探索移动端客户端应该如何交互。

## 从源码构建

需要：

- 支持本项目 Android Gradle Plugin 版本的 Android Studio。
- JDK 17。
- Android SDK 36。
- 一个用于运行时测试的 OpenCode server。

构建 debug APK：

```bash
cd android-app
./gradlew assembleDebug
```

安装到已连接设备或模拟器：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

常用验证命令：

```bash
./gradlew :app:assembleDebug
```

## 连接 OpenCode Server

### 方式 A：Server 运行在开发机上

启动 OpenCode，并让它监听网络：

```bash
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 0.0.0.0 --port 4096
```

然后在 App 中输入开发机的局域网 IP 和端口 `4096`。

如果不想把端口暴露到局域网，可以使用 USB reverse port forwarding：

```bash
adb reverse tcp:4096 tcp:4096
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 127.0.0.1 --port 4096
```

然后在 App 中使用 `127.0.0.1` 和端口 `4096`。

### 方式 B：Server 运行在 Termux 中

先安装或构建适用于 Termux 的 OpenCode runtime，然后运行：

```bash
opencode serve --hostname 127.0.0.1 --port 4096
```

当 server 能从同一个 Android 环境访问时，Android App 可以连接 `127.0.0.1:4096`。根据 OpenCode 的安装和隔离方式，你可能需要调整 host、port 或 Android 网络设置。

本仓库的 Termux packaging 研究位于 `build/`；运行时侧细节见 [build/README.md](build/README.md) 和 [build/docs/native-android-research.md](build/docs/native-android-research.md)。

### Password 和 directory

App 支持：

- 使用配置的 OpenCode password 进行可选 Basic auth。
- 通过 `x-opencode-directory` 选择工作目录。
- 使用完整 server URL，例如 `http://192.168.1.10:4096`，或分别填写 host/port。

## 仓库结构

```text
opencode-android/
├── README.md
├── README.zh-CN.md
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

## 技术栈

| 领域 | 技术 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose, Material 3 |
| 导航 | Compose Navigation |
| 网络 | Ktor Client, OkHttp, raw `HttpURLConnection` for SSE |
| 序列化 | kotlinx.serialization |
| 持久化 | AndroidX DataStore Preferences |
| Markdown | `multiplatform-markdown-renderer-m3` |
| Service 实验 | Android foreground service + `ProcessBuilder` |
| Minimum SDK | 26 |
| Target/compile SDK | 36 |

## 贡献

欢迎贡献，尤其是这些方向：

- OpenCode API compatibility。
- SSE streaming reliability。
- Android process/runtime integration。
- Termux handoff 和文档。
- coding-agent sessions 的移动端 UX。
- 测试和小型可复现示例。

请继续保持对项目成熟度的诚实描述。好的贡献应该让移动端 OpenCode workflow 更容易理解、更可靠，或更容易复现。

## License

MIT. 见 [build/LICENSE](build/LICENSE)。

## Credits

- [OpenCode](https://opencode.ai)：本 App 连接的 coding-agent runtime。
- [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)：Android/Termux runtime packaging 参考。
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)：Compose Markdown 渲染。
