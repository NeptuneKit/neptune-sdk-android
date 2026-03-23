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
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
