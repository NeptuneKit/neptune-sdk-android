pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    plugins {
        id("org.jetbrains.kotlin.jvm") version "1.9.24"
    }
}

rootProject.name = "neptune-sdk-android"
include(":sdk")
