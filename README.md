# neptune-sdk-android

NeptuneKit v2 Android SDK skeleton.

## Scope
- Log queue with stable cursor pagination and optional SQLite persistence.
- v2 ingest model aligned with the cross-platform contracts.
- Local HTTP export server backed by Ktor CIO for `GET /v2/export/health`, `GET /v2/export/metrics`, `GET /v2/logs`, `POST /v2/ui-tree/inspector`, and `POST /v2/client/command`.
- Gateway discovery with `mDNS` priority and manual `DSN` fallback.
- Client registration session that posts to `POST /v2/clients:register` on start and renews every 30 seconds.
- JVM tests for overflow, pagination, and persistence behavior.

## Layout
- `sdk/src/main/kotlin/.../model`: shared log models.
- `sdk/src/main/kotlin/.../core`: queue facade and storage abstraction.
- `sdk/src/main/kotlin/.../storage`: SQLite persistence implementation.
- `sdk/src/main/kotlin/.../export`: export service facade.
- `sdk/src/main/kotlin/.../http`: embedded HTTP export server.
- `sdk/src/main/kotlin/.../registration`: active callback registration client and renewal loop.
- `sdk/src/main/kotlin/.../discovery`: gateway discovery client, transport, and `JmDNS` resolver.
- `sdk/src/main/sqldelight/...`: SQLDelight schema and queries.
- `sdk/src/test/kotlin/...`: JVM tests.

## HTTP export server

The embedded export server now runs on Ktor Server with the official CIO engine. Route contract and JSON payloads remain unchanged for the exposed endpoints.

```kotlin
import com.neptunekit.sdk.android.createExportHttpServer

val server = createExportHttpServer(host = "127.0.0.1")
server.start(8081)

// GET http://127.0.0.1:8081/v2/export/health
// GET http://127.0.0.1:8081/v2/export/metrics
// GET http://127.0.0.1:8081/v2/logs?cursor=1&limit=50
// POST http://127.0.0.1:8081/v2/ui-tree/inspector {"platform":"android","appId":"demo.app","sessionId":"s-1","deviceId":"d-1",...}
// POST http://127.0.0.1:8081/v2/client/command {"requestId":"req-1","command":"ping"}

server.stop()
```

By default the local HTTP server binds to `0.0.0.0`. Pass `host = "127.0.0.1"` if you want loopback-only listening.

## Active Callback Model

The SDK exposes a small registration session for the callback model:

```kotlin
import com.neptunekit.sdk.android.createClientRegistrationSession
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint

val session = createClientRegistrationSession(
    gatewayEndpoint = GatewayDiscoveryEndpoint("127.0.0.1", 18765),
    callbackEndpoint = "http://10.0.2.2:8081/v2/client/command",
    platform = "android",
    appId = "com.neptunekit.sdk.android.examples.simulator",
    deviceId = "simulator-device",
    sessionId = "simulator-session",
)

session.start()
// session.close()
```

The registration payload uses `platform + appId + deviceId` as the primary identity key.
`sessionId` is carried for display and diagnostics only.

## Gateway discovery

The SDK can discover the CLI gateway in two steps:

1. Try `mDNS` candidates first.
2. Fall back to the manually configured DSN if `mDNS` fails.

Discovery probes `GET /v2/gateway/discovery` and expects a JSON payload with `host`, `port`, and `version`.

```kotlin
import com.neptunekit.sdk.android.createGatewayDiscovery
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryConfig

val discovery = createGatewayDiscovery()
val result = discovery.discover(
    GatewayDiscoveryConfig(
        manualDsn = "127.0.0.1:18765",
        mdnsServiceType = "_neptune._tcp.local.",
        timeoutMillis = 2_000,
    ),
)

println("${result.source} -> ${result.host}:${result.port} (${result.version})")
```

For tests, inject `GatewayDiscoveryMdnsResolver` and `GatewayDiscoveryHttpClient` so discovery behavior stays deterministic.

## Queue storage modes

Default behavior remains unchanged and uses the in-memory queue:

```kotlin
import com.neptunekit.sdk.android.createExportService

val service = createExportService(queueCapacity = 2_000)
```

Optional local persistence uses SQLDelight-backed SQLite storage:

```kotlin
import com.neptunekit.sdk.android.createPersistentExportService
import java.nio.file.Paths

val service = createPersistentExportService(
    databasePath = Paths.get("/tmp/neptune-export.db"),
    queueCapacity = 2_000,
)
```

In both modes, the export contract stays the same for:

- `GET /v2/export/health`
- `GET /v2/export/metrics`
- `GET /v2/logs?cursor&limit`
- `POST /v2/ui-tree/inspector`

## Development
- `./gradlew test` downloads a local Gradle distribution on first use if Gradle is not installed.
- JDK 17 is required.
- See `docs-linhay/dev/mature-library-audit.md` for the HTTP engine migration decision record.
- See `docs-linhay/features/persistent-export-queue.md` and `docs-linhay/dev/persistent-queue-storage-design.md` for the persistence requirement and design record.
- See `docs-linhay/features/smoke-demo.md` for the smoke demo acceptance scope.

## Smoke Demo

The repository includes an executable smoke demo module that sends a few logs through the public SDK API and prints a compact summary of the queue state.

Run it from the repository root:

```bash
./gradlew smokeDemo
```

You can also invoke the module directly:

```bash
./gradlew :examples:smoke-demo:run
```

The demo output includes:

- queue health
- queued record count
- dropped overflow count
- a compact record list with stable cursors

## Android Simulator Demo

An independent Android application lives under `examples/simulator-app/`. It depends on `:sdk` through composite build resolution and does not participate in the default root build graph.

Run it from the repository root:

```bash
cd examples/simulator-app
cp local.properties.example local.properties
./gradlew :app:installDebug
adb shell am start -n com.neptunekit.sdk.android.examples.simulator/.MainActivity
```

The app automatically opens `ws://10.0.2.2:18765/v2/ws` on launch, sends `hello(role=sdk)`, keeps a 15 second heartbeat, and retries on 0.5/1/2/4/8 second backoff when the socket goes stale.

The app also shows:

- a button that writes a log record through the SDK
- a live metrics panel for queue health
- logcat output tagged `NeptuneSimulatorDemo` for host-side validation

See `examples/simulator-app/README.md` for emulator setup and validation details.

## CI
- GitHub Actions runs `./gradlew test` on pushes to `main` and on pull requests.
- The workflow uses `actions/setup-java` with Gradle cache enabled and JDK 17 on `ubuntu-latest`.

## Maven Central Publish
- Workflow: `.github/workflows/publish-maven-central.yml`
- Trigger:
  - push tag（例如 `v1.2.3` 或 `2026.3.26`）
  - `workflow_dispatch`（支持 `version` 与 `dry_run`）
- Publish endpoints:
  - release: `https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/`
  - snapshot: `https://central.sonatype.com/repository/maven-snapshots/`
- Required GitHub Secrets:
  - `MAVEN_CENTRAL_USERNAME`
  - `MAVEN_CENTRAL_PASSWORD`
  - `MAVEN_CENTRAL_NAMESPACE`（通常是 groupId namespace，如 `io.github.linhay`）
  - `MAVEN_SIGNING_KEY`（ASCII-armored private key）
  - `MAVEN_SIGNING_PASSWORD`
- Notes:
  - release 发布后，workflow 会自动调用 `manual/upload/defaultRepository/<namespace>` 触发 Central Portal 可见化。
  - 如仅验证打包链路，可使用 `dry_run=true` 执行 `publishToMavenLocal`。

### Local Publish Config (Generated)

1. 复制环境变量模板：
   - `cp .env.maven-central.example .env.maven-central`
2. 填入你的 Central Portal token、namespace 与签名密钥。
3. 运行脚本：
   - dry run：`scripts/publish-maven-central-local.sh --dry-run --version 0.1.0`
   - release：`scripts/publish-maven-central-local.sh --version 0.1.0`
