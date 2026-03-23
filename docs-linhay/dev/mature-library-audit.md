# 成熟库优先审计

日期：2026-03-24

## 范围

- `sdk/src/main/kotlin/com/neptunekit/sdk/android/http/ExportHttpServer.kt`
- `sdk/src/main/kotlin/com/neptunekit/sdk/android/core/LogQueue.kt`
- `sdk/src/main/kotlin/com/neptunekit/sdk/android/export/ExportService.kt`

## 结论

仓内存在一处明显的手搓基础设施：HTTP 导出接口的 JSON 编码。原实现手工拼接 JSON 字符串，并自行处理字符串转义、空值、数组和对象结构。这类逻辑属于通用基础能力，应该交给成熟库处理。

HTTP 服务本体使用 `NanoHTTPD`，属于成熟第三方库，保留。
查询参数解析仅负责 `cursor` / `limit` 的数值提取和默认值回退，体量很小，且不涉及协议语义实现，保留。

## 处理

- 引入 `com.fasterxml.jackson.core:jackson-databind:2.17.2`
- 用 Jackson 的树模型 API 替换手写 JSON 拼接
- 增加测试覆盖：
  - 健康检查、metrics、logs、错误响应都可以被 JSON 解析
  - 日志消息中的引号和换行会被正确转义
  - `source == null` 时输出 `null`
  - 非法 `cursor` / `limit` 输入会回退到默认行为

## 依赖选择理由

- Jackson 是 Java/Kotlin 生态里非常成熟的 JSON 库
- 只需要 JSON 构造能力，不需要注解序列化或额外编译插件
- 树模型 API 适合当前仓库的 JVM SDK 场景，且不会引入 Kotlin 元数据兼容风险
- 可直接替代手工拼接，减少转义和结构错误风险

## 后续约束

- 新增 JSON/协议输出时，默认使用成熟库，不允许再手写拼接器
- 若后续引入更复杂的请求/响应协议，优先考虑同样成熟的序列化方案，而不是扩散自研解析逻辑
