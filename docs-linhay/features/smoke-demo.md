# SDK 冒烟 Demo

日期：2026-03-24

## 背景

`neptune-sdk-android` 目前只有库级测试，没有一个可直接执行的接入级冒烟链路。需要补一个最小 demo，证明 SDK 公开 API 能被外部模块接入、发送日志并输出摘要，同时不破坏现有 Gradle 构建结构。

## 验收范围

- 增加一个可执行的 smoke demo，位于 `examples` 或 `scripts` 路径下
- demo 使用 SDK 公开 API 发送日志
- demo 输出可读摘要，至少包含：
  - 已发送日志数量
  - 队列容量与当前大小
  - overflow 统计
  - 导出页摘要或消息列表
- 保持现有 Gradle 构建可用
- `./gradlew test` 通过
- smoke demo 可执行

## BDD 场景

### 场景 1：demo 可以通过 Gradle 直接执行

- Given 仓库已完成依赖解析
- When 执行 smoke demo 入口
- Then demo 成功启动
- And 结束时输出摘要

### 场景 2：demo 能通过 SDK API 发送日志

- Given demo 持有一个 `ExportService`
- When 依次调用 SDK 公开 API 写入日志
- Then 队列中出现对应记录
- And 可通过 `logs()` 或 `metrics()` 读取到结果

### 场景 3：demo 输出稳定摘要

- Given demo 已发送固定数量的日志
- When 读取健康信息、metrics 和日志页
- Then 输出文本摘要包含关键统计信息
- And 运行结果可用于人工冒烟检查
