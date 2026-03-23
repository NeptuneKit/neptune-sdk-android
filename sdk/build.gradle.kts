plugins {
    id("org.jetbrains.kotlin.jvm")
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

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
