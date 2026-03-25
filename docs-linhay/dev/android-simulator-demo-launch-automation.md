# Android 模拟器 Demo 启动自动化

日期：2026-03-25

## 背景

本机曾出现两类问题：

1. `ANDROID_SDK_ROOT` 指向了不存在的路径，导致命令行找不到 `emulator`
2. `adb devices` 没有在线设备，因为 emulator 没有被真正启动起来

同时，仓库里已经存在一个可安装的 Demo：

- `examples/simulator-app/`
- 包名：`com.neptunekit.sdk.android.examples.simulator`

## 结论

新增 `scripts/start-simulator-demo.sh` 作为统一入口，按以下顺序执行：

1. 解析 SDK 根目录
2. 检查 `emulator` 和 `adb`
3. 列出并选择 AVD
4. 选择未占用的 console port，优先以 `lsof` 检查监听端口，再结合 adb 状态
5. 启动 emulator
6. 等待 adb 进入 `device` 状态并完成 boot
7. 通过 Gradle 安装 demo app
8. 用 `adb shell am start` 启动 `MainActivity`

## 诊断规则

脚本优先读取：

- `ANDROID_SDK_ROOT`
- `ANDROID_HOME`
- `examples/simulator-app/local.properties` 里的 `sdk.dir`

只要这些候选项中有一个指向有效 SDK 根目录，脚本就会继续。
有效 SDK 根目录需要同时包含：

- `emulator/emulator`
- `platform-tools/adb`

## AVD 缺失时的行为

如果没有找到任何 AVD，脚本会直接失败，并给出创建步骤，而不是静默退出。

推荐命令：

```bash
sdkmanager --install "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n Neptune_API_34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
```

## 验证

建议先执行：

```bash
./scripts/start-simulator-demo.sh --dry-run
```

再执行正式启动：

```bash
./scripts/start-simulator-demo.sh
```
