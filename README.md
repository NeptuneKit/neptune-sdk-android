# neptune-sdk-android

NeptuneKit v2 Android SDK skeleton.

## Scope
- In-memory log queue with stable cursor pagination.
- v2 ingest model aligned with the cross-platform contracts.
- Local HTTP export server backed by Ktor CIO for `GET /v2/export/health`, `GET /v2/export/metrics`, and `GET /v2/export/logs`.
- JVM tests for overflow and pagination behavior.

## Layout
- `sdk/src/main/kotlin/.../model`: shared log models.
- `sdk/src/main/kotlin/.../core`: in-memory queue.
- `sdk/src/main/kotlin/.../export`: export service facade.
- `sdk/src/main/kotlin/.../http`: embedded HTTP export server.
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

## Development
- `./gradlew test` downloads a local Gradle distribution on first use if Gradle is not installed.
- JDK 17 is required.
- See `docs-linhay/dev/mature-library-audit.md` for the HTTP engine migration decision record.
