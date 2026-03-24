plugins {
    id("org.jetbrains.kotlin.jvm")
    id("app.cash.sqldelight") version "2.0.2"
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val ktorVersion = "2.3.12"
    val sqlDelightVersion = "2.0.2"

    implementation("app.cash.sqldelight:runtime:$sqlDelightVersion")
    implementation("app.cash.sqldelight:sqlite-driver:$sqlDelightVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(kotlin("test"))
}

sqldelight {
    databases {
        create("NeptuneQueueDatabase") {
            packageName.set("com.neptunekit.sdk.android.storage.db")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
