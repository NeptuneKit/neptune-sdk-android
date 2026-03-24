plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.neptunekit.sdk.android.examples.SmokeDemoKt")
}

dependencies {
    implementation(project(":sdk"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
