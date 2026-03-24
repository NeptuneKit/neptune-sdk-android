allprojects {
    group = "com.neptunekit.sdk.android"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs all project tests."
    dependsOn(":sdk:test", ":examples:smoke-demo:test")
}

tasks.register("check") {
    group = "verification"
    description = "Runs all verification tasks."
    dependsOn(":sdk:check", ":examples:smoke-demo:check")
}

tasks.register("smokeDemo") {
    group = "verification"
    description = "Runs the SDK smoke demo."
    dependsOn(":examples:smoke-demo:run")
}
