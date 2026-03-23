# neptune-sdk-android

NeptuneKit v2 Android SDK skeleton.

## Scope
- In-memory log queue with stable cursor pagination.
- v2 ingest model aligned with the cross-platform contracts.
- JVM tests for overflow and pagination behavior.

## Layout
- `sdk/src/main/kotlin/.../model`: shared log models.
- `sdk/src/main/kotlin/.../core`: in-memory queue.
- `sdk/src/main/kotlin/.../export`: export service facade.
- `sdk/src/test/kotlin/...`: JVM tests.

## Development
- `./gradlew test` downloads a local Gradle distribution on first use if Gradle is not installed.
- JDK 17 is required.
