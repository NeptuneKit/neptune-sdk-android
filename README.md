# neptune-sdk-android

NeptuneKit v2 Android SDK skeleton.

## Scope
- Log queue with stable cursor pagination and optional SQLite persistence.
- v2 ingest model aligned with the cross-platform contracts.
- Local HTTP export server backed by Ktor CIO for `GET /v2/export/health`, `GET /v2/export/metrics`, and `GET /v2/export/logs`.
- JVM tests for overflow, pagination, and persistence behavior.

## Layout
- `sdk/src/main/kotlin/.../model`: shared log models.
- `sdk/src/main/kotlin/.../core`: queue facade and storage abstraction.
- `sdk/src/main/kotlin/.../storage`: SQLite persistence implementation.
- `sdk/src/main/kotlin/.../export`: export service facade.
- `sdk/src/main/kotlin/.../http`: embedded HTTP export server.
- `sdk/src/main/sqldelight/...`: SQLDelight schema and queries.
- `sdk/src/test/kotlin/...`: JVM tests.

## HTTP export server

The embedded export server now runs on Ktor Server with the official CIO engine. Route contract and JSON payloads remain unchanged.

```kotlin
import com.neptunekit.sdk.android.createExportHttpServer

val server = createExportHttpServer()
server.start(8081)

// GET http://127.0.0.1:8081/v2/export/health
// GET http://127.0.0.1:8081/v2/export/metrics
// GET http://127.0.0.1:8081/v2/export/logs?cursor=1&limit=50

server.stop()
```

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
- `GET /v2/export/logs?cursor&limit`

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

## CI
- GitHub Actions runs `./gradlew test` on pushes to `main` and on pull requests.
- The workflow uses `actions/setup-java` with Gradle cache enabled and JDK 17 on `ubuntu-latest`.
