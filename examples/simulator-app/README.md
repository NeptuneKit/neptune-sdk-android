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

进入 `examples/simulator-app/` 目录后执行：

```bash
cd examples/simulator-app
./gradlew :app:installDebug
```

启动 Activity：

```bash
adb shell am start -n com.neptunekit.sdk.android.examples.simulator/.MainActivity
```

## 验证方式

### 方式 1：adb logcat

点击 Demo 中的“写入日志”按钮后，过滤日志：

```bash
adb logcat | rg NeptuneSimulatorDemo
```

日志会包含当前 click 次数、queue size 和 metrics 摘要。

### 方式 2：屏幕验证

按钮下方的 metrics 面板会立即刷新，显示：

- `health`
- `queuedRecords`
- `droppedOverflow`
- 最近一条日志消息

## 说明

这个 Demo 只负责验证宿主 App 接入 SDK 的最小闭环，不进入根仓默认构建链路。
