plugins {
    id("org.jetbrains.kotlin.jvm")
    id("app.cash.sqldelight") version "2.0.2"
    `java-library`
    `maven-publish`
    signing
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "neptune-sdk-android"

            pom {
                name.set("neptune-sdk-android")
                description.set("NeptuneKit v2 Android SDK")
                url.set("https://github.com/NeptuneKit/neptune-sdk-android")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("neptunekit")
                        name.set("NeptuneKit")
                        url.set("https://github.com/NeptuneKit")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/NeptuneKit/neptune-sdk-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/NeptuneKit/neptune-sdk-android.git")
                    url.set("https://github.com/NeptuneKit/neptune-sdk-android")
                }
            }
        }
    }

    repositories {
        maven {
            val releaseURL = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            val snapshotURL = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotURL else releaseURL)

            credentials {
                username = (findProperty("MAVEN_CENTRAL_USERNAME") as String?) ?: System.getenv("MAVEN_CENTRAL_USERNAME")
                password = (findProperty("MAVEN_CENTRAL_PASSWORD") as String?) ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = (findProperty("signingKey") as String?) ?: System.getenv("MAVEN_SIGNING_KEY")
    val signingPassword = (findProperty("signingPassword") as String?) ?: System.getenv("MAVEN_SIGNING_PASSWORD")
    val releaseBuild = !version.toString().endsWith("SNAPSHOT")

    isRequired = releaseBuild
    if (releaseBuild && (signingKey.isNullOrBlank() || signingPassword.isNullOrBlank())) {
        throw org.gradle.api.GradleException("Release publish requires signingKey/signingPassword for Maven Central.")
    }
    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}
