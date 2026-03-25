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
    val okhttpVersion = "4.12.0"
    val sqlDelightVersion = "2.0.2"

    implementation("app.cash.sqldelight:runtime:$sqlDelightVersion")
    implementation("app.cash.sqldelight:sqlite-driver:$sqlDelightVersion")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.jmdns:jmdns:3.6.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
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
