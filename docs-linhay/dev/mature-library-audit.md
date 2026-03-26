# 成熟库优先审计与 HTTP 引擎迁移决策

日期：2026-03-24

## 范围

- `sdk/src/main/kotlin/com/neptunekit/sdk/android/http/ExportHttpServer.kt`
- `sdk/src/main/kotlin/com/neptunekit/sdk/android/export/ExportService.kt`
- `sdk/src/test/kotlin/com/neptunekit/sdk/android/http/ExportHttpServerTest.kt`

## 验收边界

- 保持现有导出接口不变：
  - `GET /v2/export/health`
  - `GET /v2/export/metrics`
  - `GET /v2/logs?cursor&limit`
- 保持现有错误语义不变：
  - 已知路径的非 `GET` 请求返回 `405` JSON 错误
  - 未知路径返回 `404` JSON 错误
- JSON 输出继续由成熟方案生成，不回退到手写拼接
- `./gradlew test` 通过

## 结论

HTTP 导出服务从 `NanoHTTPD` 迁移到 `Ktor Server + CIO`。

保留项：

- `ExportHttpRouter` 继续负责路由分发、查询参数解析和错误语义
- JSON 编码继续使用 Jackson
- 对外公开 API 继续是 `ExportHttpServer.start(port)` / `stop()`

替换项：

- 底层监听与请求处理从 `NanoHTTPD` 切换为 Ktor 官方 Server 引擎
- 路由结果不再依赖 `NanoHTTPD.Response.Status`，统一收敛为纯 HTTP 状态码

## 为什么选 Ktor 而不是继续保留 NanoHTTPD

- Ktor 是 Kotlin/JVM 生态内维护更活跃、扩展面更完整的服务端方案
- 当前仓库已经是 Kotlin/JVM，直接接入 Ktor 的依赖、调试和测试成本最低
- Ktor 的请求/响应模型、嵌入式启动方式、后续插件能力都更适合继续演进
- 本次只替换传输层，不需要重写导出服务或路由行为，迁移风险可控

## 为什么本次选 CIO，而不是 Netty

这里的决策是针对“Android SDK 内嵌本地 HTTP 导出服务”这个场景，而不是通用后端服务。

- Netty 在传统 JVM 服务端生态里更常见，但它为独立服务进程设计得更重
- 当前仓库是 Kotlin/JVM SDK，HTTP 服务会跟随宿主 App 进程一起启动，优先考虑依赖面、接入摩擦和嵌入式使用成本
- CIO 是 Ktor 官方引擎，足够成熟，并且与 Ktor 自身协程模型贴合更直接
- 对当前只需要本地只读导出接口的场景，CIO 已能满足稳定性和维护性要求

推论：

- 如果未来把导出服务拆成独立 JVM 服务进程，再重新比较 `Ktor + Netty` 与 `Ktor + CIO` 是合理的
- 但在当前仓库边界内，`Ktor + CIO` 是更稳妥的成熟库组合

## GRDB 7+ 评估

已知信息：Swift 社区在 2026-02-15 发布的 `GRDB 7.10.0` 说明里提到开始支持 Android、Linux、Windows 和 SQLCipher+SPM。来源：Swift Forums 的发布帖。

为什么本次仍不选 GRDB：

- 当前仓库是 Kotlin/JVM，不是 SwiftPM / Apple 平台主导工程
- 本次任务是 HTTP 导出层替换，GRDB 解决的是数据库访问问题，不直接覆盖 HTTP server 能力
- 若在本仓直接引入 GRDB，需要额外解决 Swift 与 Kotlin/JVM 的边界问题，这会把一次“HTTP 服务器替换”扩展成“跨语言运行时集成”
- 即便 GRDB 已具备 Android 方向能力，它在本仓的落地点也更像“可选持久化层实验”，而不是“替代 HTTP 层的成熟方案”

因此，本次迁移仍以 Ktor 作为 HTTP 层最成熟、最贴合当前技术栈的选择。

## 如果后续要在本仓探索 GRDB，可落地路径

这条路径不阻塞当前迁移，只作为后续演进选项：

1. 先保持当前 HTTP 导出契约稳定，不把持久化与导出接口耦合
2. 在 Kotlin 侧抽出日志存储 SPI，例如 `LogStore`
3. 先落一个 Kotlin/JVM 原生可维护的持久化实现，验证分页、游标和回放语义
4. 只有当确实需要跨语言共享 SQLite 行为时，再评估：
   - 将 GRDB 放到独立实验模块
   - 明确 Swift 构建链、Android 打包方式和 JNI/桥接方案
   - 用兼容性测试证明它比 Kotlin 原生方案更有收益

这意味着，GRDB 更适合作为“持久化层研究方向”，而不是当前 HTTP 迁移的阻塞前提。

## 测试策略

- 保留原有路由级单元测试，继续验证：
  - 健康检查、metrics、logs、错误响应都可被 JSON 解析
  - 日志消息中的引号和换行被正确转义
  - `source == null` 时输出 `null`
  - 非法 `cursor` / `limit` 输入回退到默认行为
- 新增真实起服的 HTTP 集成测试，验证：
  - `GET /v2/export/health` 可通过本地端口访问
  - `POST /v2/export/health` 仍返回 `405` JSON 错误

## 后续约束

- 新增 HTTP 基础设施时，优先选择 Kotlin/JVM 主流成熟库，而不是轻量自研或长期维护风险更高的冷门库
- 路由行为变化必须先补测试，再修改实现
- 若未来引入持久化层，不得把“数据库选型”与“HTTP 服务选型”混成一个提交
