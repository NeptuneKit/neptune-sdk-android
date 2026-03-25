# 模拟器 Demo App

日期：2026-03-24

## 背景

`neptune-sdk-android` 目前只有 JVM smoke demo，缺少一个真正可以安装到 Android Emulator 上的接入示例。需要补一个独立 Android 应用工程，证明 SDK 可以在宿主 App 中被直接依赖、触发写日志并展示 metrics，而不影响根仓的常规构建链路。

## 目标

- 在 `examples/simulator-app/` 下提供独立 Android application 工程
- Demo 直接依赖上层仓库的 `:sdk`，不复制 SDK 代码
- 启动后提供最小 Activity、按钮和 metrics 文本
- 支持安装到 Android Emulator 后手动验证
- 不把 Demo 强绑定到根仓的默认 `./gradlew test` 与 `./gradlew smokeDemo`

## 验收范围

- Demo 工程可独立配置并编译
- Demo Activity 至少包含：
  - 一个“发现网关”按钮
  - 一个“写入日志”按钮
  - 一个显示 metrics / health 的文本区域
- 发现网关后：
  - 通过 Neptune SDK discovery 入口发现 CLI 网关
  - 文本区域显示 `source`、`host`、`port`、`version` 或错误原因
  - 若发现成功，自动向 CLI 网关 `POST /v2/logs:ingest` 上报一条结构化日志
  - 文本区域与 `adb logcat` 都能看到上传结果
- 点击按钮后：
  - 通过 Neptune SDK 写入一条日志
  - metrics 文本立即刷新
- 提供运行说明：
  - Emulator 创建方式
  - 安装与启动命令
  - 使用 `adb logcat` 或本地 HTTP export 进行验证
- 根仓现有 `./gradlew test` 和 `./gradlew smokeDemo` 不受影响

## BDD 场景

### 场景 1：模拟器 Demo 能独立构建

- Given 上层仓库的 `:sdk` 可通过 composite build 解析
- When 构建 `examples/simulator-app`
- Then Android application 工程成功解析 SDK 依赖
- And 产物可安装到 Emulator

### 场景 2：按钮触发 SDK 写日志

- Given Demo 已启动并持有 `ExportService`
- When 用户点击“写入日志”按钮
- Then SDK 接收到一条 `IngestLogRecord`
- And metrics / queue size 随之更新

### 场景 3：按钮触发网关发现

- Given Demo 已启动且可以访问本地 CLI 网关
- When 用户点击“发现网关”按钮
- Then Demo 调用 discovery 入口并返回 `source / host / port / version`
- And 若发现成功，自动调用 `POST /v2/logs:ingest`
- And 文本区域显示发现结果、上传结果或错误原因

### 场景 4：Demo 可用于人工冒烟

- Given Demo 已安装并启动到 Emulator
- When 用户点击按钮并查看输出
- Then 屏幕上能看到最新 metrics 文本
- And 可通过 `adb logcat` 或 HTTP export 观察到同一批日志与上传结果
