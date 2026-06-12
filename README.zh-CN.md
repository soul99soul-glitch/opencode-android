# opencode-android

[English](README.md)

一个用 Jetpack Compose 写的 [OpenCode](https://opencode.ai) Android 客户端。

它能连接已经运行的 OpenCode server，在手机上查看会话、发送消息、接收流式回复。你可以把它理解成 OpenCode 的移动端界面，而不是另一个通用聊天 App。

> [!IMPORTANT]
> 项目还在早期阶段，目前没有稳定版。局域网模式仍然是最稳妥的路径：先在电脑或 Termux 中运行 `opencode serve`，再让 App 连接过去。Bundled Local 模式仍属实验功能，源码构建前需要先导入 runtime payload。

本项目与 OpenCode 官方无关。

## 现在能做什么

- 连接指定的 OpenCode server，支持完整 URL 或 host 与 port
- 使用 password 进行 Basic Auth，并通过 `x-opencode-directory` 指定工作目录
- 创建、查看和删除会话
- 收发消息，通过 SSE 接收流式事件
- 在生成过程中显示文本，完成后渲染 Markdown
- 选择 server 提供的 agent 和 model
- 从 OpenCode skills 生成斜杠命令补全
- 准备图片和文件附件
- 展示工具调用，并提供子会话跳转入口
- 保存连接、外观、agent 和 model 设置
- 切换浅色、深色主题和强调色
- 实验性的 Bundled Local 模式：主 App 可以打包 arm64 OpenCode runtime，并由 `OpenCodeService` 托管启动

## 还没做好什么

- arm64 测试设备之外的 Bundled Local 兼容性
- 在激进 OEM Android 后台限制下，local runtime 的生产级保活体验
- 稳定的 Termux 交接流程
- 网络切换和 Android 后台限制下的 SSE 重连
- 正式签名、发布和自动更新
- 完整的自动化测试

当前 local runtime 架构是 Option C：主 App 打包 native runtime 和 support assets，将可执行体解压到 `nativeLibraryDir`，并由 `OpenCodeService` 启动。

```text
<nativeLibraryDir>/libopencode_runtime.so
assets/runtime_support/
```

Runtime payload 不提交进 git。构建包含本地 runtime 的 APK 前，需要先运行 `android-app/runtime/tools/` 下的导入脚本；如果缺少二进制或必要 support assets，Gradle 会 fail-fast。

## 从源码构建

需要 JDK 17、Android SDK 36，以及能构建当前 Android Gradle Plugin 版本的 Android Studio。

```bash
cd android-app
./gradlew assembleDebug
```

生成的 APK 在：

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备或模拟器：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 连接 OpenCode server

### Server 在电脑上

让 OpenCode 监听局域网地址：

```bash
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 0.0.0.0 --port 4096
```

然后在 App 中填写电脑的局域网 IP 和端口 `4096`。

如果通过 USB 调试，不想把端口开放到局域网，可以使用 ADB 反向端口转发：

```bash
adb reverse tcp:4096 tcp:4096
OPENCODE_SERVER_PASSWORD="" opencode serve --hostname 127.0.0.1 --port 4096
```

此时 App 连接 `127.0.0.1:4096`。

### Server 在 Termux 中

先准备能在 Termux 中运行的 OpenCode，再启动 server：

```bash
opencode serve --hostname 127.0.0.1 --port 4096
```

App 通常可以连接 `127.0.0.1:4096`。如果你的 OpenCode 运行在额外的隔离环境中，地址和端口可能需要调整。

`build/` 目录保存了 Termux 和 Android runtime 的打包研究。具体内容见 [build/README.md](build/README.md) 和 [build/docs/native-android-research.md](build/docs/native-android-research.md)。其中一部分参考了 [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)。

## 项目结构

```text
opencode-android/
├── android-app/    # Android 客户端
│   └── app/src/
│       ├── main/   # Compose UI、API、数据和 service
│       └── test/   # 单元测试
└── build/          # Termux 和 Android runtime 打包实验
```

Android App 的主要代码位于 `android-app/app/src/main/java/com/opencode/android/`：

- `data/api/OpenCodeApi.kt`：OpenCode HTTP API 和 SSE 客户端
- `data/model/Models.kt`：接口数据模型
- `data/repository/`：DataStore 设置
- `ui/`：Compose 页面和组件
- `service/`：本地 runtime 启动实验

## 技术栈

| 项目 | 使用的技术 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose, Material 3 |
| 导航 | Navigation Compose |
| 网络 | Ktor Client, OkHttp, `HttpURLConnection` |
| 序列化 | kotlinx.serialization |
| 本地设置 | AndroidX DataStore |
| Markdown | multiplatform-markdown-renderer |
| 最低 Android 版本 | API 26 |
| 编译和目标版本 | API 36 |

## 参与开发

现阶段最需要处理的是 OpenCode API 兼容、SSE 稳定性、本地 runtime、Termux 连接体验和测试。如果你准备提交改动，请先确认 App 连接的是哪个 OpenCode 版本，并附上复现步骤。

## License

[MIT](build/LICENSE)

## 致谢

- [OpenCode](https://opencode.ai)
- [Hope2333/opencode-termux](https://github.com/Hope2333/opencode-termux)
- [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)
