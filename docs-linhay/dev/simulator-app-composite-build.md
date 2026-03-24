# 模拟器 Demo 的 Composite Build 方案

日期：2026-03-24

## 结论

`examples/simulator-app/` 采用独立 Gradle build，通过 composite build 依赖上层仓库的 `:sdk` 模块。Demo 不进入根仓 `settings.gradle.kts`，因此不会改变 `./gradlew test` 与 `./gradlew smokeDemo` 的默认执行范围。

## 设计目标

- 保持 Demo 与主构建链路解耦
- 复用 `:sdk` 的真实源码与测试过的产物语义
- 避免复制 SDK 代码或维护第二份同步逻辑
- 让 Android Studio / 命令行都能单独打开 Demo 工程

## 目录结构

```text
examples/simulator-app/
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/...
```

## 依赖方式

- Demo build 的 `settings.gradle.kts` 通过 `includeBuild("../..")` 引入上层仓库
- App 模块依赖 `com.neptunekit.sdk.android:sdk`
- Gradle composite build 自动把该坐标替换到上层仓库的 `:sdk`

这样做的好处：

- Demo 可以独立打开
- SDK 代码只维护一份
- 根仓不需要新增 Android 插件或 Android 相关任务

## App 运行策略

- Demo 仅依赖 SDK 的核心写入与 metrics 能力
- UI 层负责把按钮事件映射为 `ingest`
- metrics 文本由本地状态渲染，便于在 Emulator 上直接确认
- 若设备环境允许，后续可再扩展 HTTP export 的本机验证，但不作为当前必须条件

## 风险

- Android SDK 未安装时，Demo build 无法真正执行到安装步骤
- SDK 的部分 JVM 依赖可能对 Android 构建有约束，因此需要保持 Demo 入口足够轻，优先验证核心 API 而不是引入额外复杂度

