# 导出队列持久化设计

日期：2026-03-24

## 结论

本次持久化方案选择 `SQLDelight + SQLite`，不选 `Room`。

## 选型理由

- 当前 `sdk` 模块是 `kotlin-jvm`，不是 Android Library；`Room` 会把实现绑定到 AndroidX 与 Android 运行时。
- `SQLDelight` 同时覆盖 Kotlin/JVM 与后续 Android SQLite 场景，适合 SDK 的长期维护。
- SQL schema、查询和迁移能和代码一起管理，便于验证 `cursor`、容量淘汰和 overflow 统计。
- `SQLDelight` 在本仓可以直接通过 Gradle 插件生成查询接口，测试阶段也能稳定运行 `./gradlew test`。

## 实现边界

- 抽出统一的日志存储抽象，供 `ExportService` 使用
- 保留默认内存实现，确保现有公开工厂函数兼容
- 新增基于 SQLite 的可选持久化实现
- 现有 HTTP 层只消费 `ExportService`，不感知底层存储类型

## 数据模型

### `log_records`

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- 记录日志导出字段
- `attributes` / `source` 采用 JSON 字符串持久化

### `queue_state`

- 单行状态表
- 保存：
  - `capacity`
  - `dropped_overflow`

## 队列语义

- 入队先插入记录，再检查总量是否超过 `capacity`
- 超出时删除最旧的若干条记录
- 每淘汰一条，`dropped_overflow` 累加并落库
- 分页查询以 `id > cursor`、`ORDER BY id ASC`、`LIMIT ?` 实现稳定游标

## 测试策略

- 保留现有内存模式测试
- 新增持久化模式测试，覆盖：
  - 入队后分页
  - overflow 持久化
  - 重新打开数据库后的状态恢复
  - 默认工厂函数仍走内存模式
