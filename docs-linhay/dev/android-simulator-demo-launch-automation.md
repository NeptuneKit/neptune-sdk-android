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
9. 在脚本内用同一个 `adb` 做驻留轮询（默认 30 秒），避免 PATH `adb` 版本不一致造成假阴性
10. 在脚本内 pin `PATH` 到解析出的 `platform-tools`，并可选启用 `--keepalive-seconds` 持续监测与自动重建 `adb forward tcp:28766 -> tcp:18766`

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

如果要显式控制驻留检查时长或保留 emulator 日志：

```bash
./scripts/start-simulator-demo.sh --residency-seconds 30 --keep-emulator-log
```

如果要在启动成功后继续保活并自愈 forward（推荐用于网关联调）：

```bash
./scripts/start-simulator-demo.sh --headless --residency-seconds 60 --keepalive-seconds 180 --keep-emulator-log
```

## 环境重建

如果本机 `adb`、`emulator` 或 AVD 状态本身已经坏掉，先重装 SDK 组件，再重建 AVD：

```bash
sdkmanager --install emulator platform-tools
sdkmanager --install "system-images;android-34;google_apis;arm64-v8a"
adb kill-server
adb start-server
```

如果 AVD 反复离线或启动失败，可以删除并重新创建：

```bash
avdmanager delete avd -n Neptune_API_34
avdmanager create avd -n Neptune_API_34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
```

## 2026-03-27 稳定性专项补充

定位到两类高频风险：

1. 系统中同时存在多个 adb（例如 Homebrew 与 SDK 内），外部命令容易使用到非脚本 adb。
2. `adb forward` 会在 adb server 抖动后丢失，导致 `127.0.0.1:28766` 回调失效，网关请求 inspector 时出现 connection refused。
3. 历史离线 emulator（如 `emulator-5554 offline`）会干扰 `-e` 语义，导致部分自动设置命令失败。

脚本侧新增防护：

- 启动后 `pin_adb_path`：将当前脚本进程 `PATH` 前置到解析出的 `ADB_BIN` 所在目录，减少同一流程中的多 adb 混用。
- 驻留检测期间增加 `ensure_forward`：发现 forward 丢失会立即重建。
- 新增 `--keepalive-seconds`：启动成功后持续监测 emulator/adb/forward，并在 forward 缺失时自动修复。
