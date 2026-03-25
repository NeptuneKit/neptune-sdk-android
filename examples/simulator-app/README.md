# Neptune Simulator App

独立 Android Demo 工程，用于在 Android Emulator 上验证 `neptune-sdk-android` 的接入路径。

## 目录

- `app/`：Android application 模块
- `settings.gradle.kts`：通过 composite build 引入上层仓库的 `:sdk`

## 前置条件

- 已安装 Android SDK
- 已安装 `platform-tools` 和 `emulator`
- 已存在一个可启动的 Emulator AVD
- 在 `examples/simulator-app/local.properties` 中配置 `sdk.dir`，或者导出 `ANDROID_HOME` / `ANDROID_SDK_ROOT`

## 打开方式

可以直接用 Android Studio 打开 `examples/simulator-app/`。

如果使用命令行，先进入 `examples/simulator-app/`：

```bash
./gradlew help
```

如果目录中还没有 `local.properties`，先复制模板：

```bash
cp local.properties.example local.properties
```

## 安装与运行

优先使用仓库根目录下的自动化脚本，它会自动解析 SDK、选择 AVD、启动 emulator、等待 adb 在线并安装/启动 Demo：

```bash
./scripts/start-simulator-demo.sh
```

如果需要先检查脚本会用到哪些路径，可以先运行：

```bash
./scripts/start-simulator-demo.sh --dry-run
```

如果需要手工执行，进入 `examples/simulator-app/` 目录后运行：

```bash
cd examples/simulator-app
./gradlew :app:installDebug
```

启动 Activity：

```bash
adb shell am start -n com.neptunekit.sdk.android.examples.simulator/.MainActivity
```

当前脚本和手工流程都依赖有效 SDK 根目录。仓库内 `examples/simulator-app/local.properties` 会优先提供 `sdk.dir`，也可以改用 `ANDROID_SDK_ROOT` / `ANDROID_HOME`。

## 验证方式

### 方式 -1：启动即连 WebSocket

App 启动后会自动尝试连接 `ws://10.0.2.2:18765/v2/ws`，发送 `hello(role=sdk)`，并按 15 秒心跳、45 秒失联和 0.5/1/2/4/8 秒重连策略维持会话。

### 方式 0：Gateway discovery

先点击“发现网关”按钮，Demo 会尝试按 `mDNS -> manual DSN -> /v2/gateway/discovery` 的顺序发现 CLI 网关。
如果发现成功，Demo 会立即向该网关 `POST /v2/logs:ingest` 上传一条结构化日志，并把上传结果显示在界面和 `adb logcat` 中。

- 默认 manual DSN: `10.0.2.2:18765`（Android Emulator 访问宿主机 loopback 的固定地址）
- 成功后屏幕会显示 `source / host / port / version`
- 成功后还会显示 `ingest` 的 `endpoint / responseCode / status`
- 失败时会在同一块文本区域显示错误原因，或显示上传被跳过

### 方式 1：adb logcat

点击 Demo 中的“写入日志”按钮后，过滤日志：

```bash
adb logcat | rg NeptuneSimulatorDemo
```

日志会包含当前 click 次数、queue size、metrics 摘要，以及 discovery / ingest 状态。

### 方式 2：屏幕验证

按钮下方的 metrics 面板会立即刷新，显示：

- `health`
- `queuedRecords`
- `droppedOverflow`
- 最近一条日志消息

## 说明

这个 Demo 只负责验证宿主 App 接入 SDK 的最小闭环，不进入根仓默认构建链路。
