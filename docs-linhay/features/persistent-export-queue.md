# 导出队列可选本地持久化

日期：2026-03-24

## 背景

当前 `sdk` 模块仅提供内存日志队列。宿主进程退出后，导出队列中的记录、游标进度和 overflow 统计都会丢失，无法满足本地缓冲与离线排查场景。

本次需求是在不破坏现有 HTTP 导出契约的前提下，为日志导出队列增加“可选本地持久化存储”能力，并保持默认行为与现状兼容。

## 验收范围

- 保持以下导出接口契约不变：
  - `GET /v2/export/health`
  - `GET /v2/export/metrics`
  - `GET /v2/export/logs?cursor&limit`
- 默认仍使用内存模式，现有调用方不需要修改代码即可维持当前行为
- 新增持久化模式，允许把队列数据落到本地 SQLite
- 持久化模式至少支持：
  - 入队持久化
  - `cursor` / `limit` 查询
  - 容量上限淘汰
  - overflow 计数持久化

## BDD 场景

### 场景 1：默认保持内存模式

- Given 调用方继续使用 `createExportService(queueCapacity)` 或 `LogQueue(capacity)`
- When 宿主不显式声明持久化存储
- Then 队列行为与当前版本一致
- And 导出接口的 JSON 结构与字段保持不变

### 场景 2：持久化模式入队后可分页导出

- Given 调用方显式创建本地持久化队列
- When 依次写入多条日志
- Then `GET /v2/export/logs?cursor&limit` 仍按稳定递增游标返回结果
- And `nextCursor` / `hasMore` 语义与内存模式一致

### 场景 3：持久化模式跨实例保留状态

- Given 同一个数据库文件被重新打开
- When 新实例读取导出队列和指标
- Then 已入队的日志仍然存在
- And `droppedOverflow` 延续之前的累计值
- And 后续新写入日志的游标继续单调递增

### 场景 4：容量上限触发淘汰并持久化 overflow

- Given 持久化队列容量为 `3`
- When 连续写入 `4` 条日志
- Then 最旧的一条日志会被淘汰
- And `queuedRecords == 3`
- And `droppedOverflow == 1`
- And 重新打开数据库后上述指标仍保持一致
