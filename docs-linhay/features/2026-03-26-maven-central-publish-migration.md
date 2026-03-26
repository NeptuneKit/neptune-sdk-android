# Maven Central 发布迁移（OSSRH EOL）

## 背景
- Sonatype 官方文档说明：`OSSRH` 已于 `2025-06-30` 结束服务。
- 现有发布配置仍使用 `s01.oss.sonatype.org`，需要迁移到 Central Portal 兼容发布链路。

## 目标
- 保持当前 Gradle `maven-publish + signing` 方案不变。
- 仅替换发布端点并补充 Central Portal 可见化触发步骤。
- 保持现有版本号与签名策略。

## BDD 验收场景

### 场景 1：标签发布 release 版本
- Given 仓库推送 tag（如 `v1.2.3`）
- And GitHub Secrets 已配置 Central Portal token 与签名密钥
- When 执行 `publish-maven-central` 工作流
- Then 产物发布到 `ossrh-staging-api.central.sonatype.com`
- And 触发 `manual/upload/defaultRepository/<namespace>` 后在 Central Portal 可见

### 场景 2：手动 dry run
- Given 从 `workflow_dispatch` 启动并设置 `dry_run=true`
- When 工作流执行
- Then 仅执行 `:sdk:publishToMavenLocal`
- And 不触发远端上传

### 场景 3：本地开发验证
- Given 本地无 Central 凭证
- When 执行 `./gradlew :sdk:test :sdk:publishToMavenLocal -PVERSION_NAME=0.1.0-SNAPSHOT`
- Then 测试与本地发布任务通过

## 非目标
- 不切换到第三方 Portal Gradle 插件。
- 不调整 artifact 坐标与 POM 元数据结构。
