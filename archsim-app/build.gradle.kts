// The ArchSim application: the HTTP API on Spring Boot 4, one deployable started as `api` or
// `migrate` (ADR-0014), over the same PostgreSQL tables as the Python API (ADR-0008). Stage 2 of
// the Java port; see README.md.
plugins {
    id("archsim.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jib)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // one Jackson for the app and the model, whatever Spring Boot's BOM says
    implementation(platform(libs.jackson.bom))
    implementation(project(":archsim-model"))
    implementation(project(":archsim-engine"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.httpclient5)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.archunit)
    // the black-box suite's stand-in GitHub, for the sign-in tests
    testImplementation(project(":archsim-api-tests"))
    // PostgreSQL binaries from Maven Central for the database tests, no Docker (ADR-0033)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
}

tasks.test {
    // the examples, the golden files and the Python engine's price snapshots
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}

tasks.bootJar {
    // one name whatever the version, so the black-box suite and the image find it
    archiveFileName = "archsim-app.jar"
}

// The image compose runs as `migrate` and `api` (ADR-0014), built into the local Docker with
// ./gradlew :archsim-app:jibDockerBuild (make image), for this machine's processor. It carries the
// examples, as the Python image did.
val imageExamples = "/app/examples"
val buildsOnArm = providers.systemProperty("os.arch").get() in setOf("aarch64", "arm64")
val imageArchitecture = if (buildsOnArm) "arm64" else "amd64"

jib {
    from {
        image = "eclipse-temurin:25-jre"
        platforms {
            platform {
                architecture = imageArchitecture
                os = "linux"
            }
        }
    }
    to {
        image = "archsim-app:local"
    }
    container {
        mainClass = "io.archsim.app.ArchsimApplication"
        args = listOf("api")
        ports = listOf("8000")
        // a user and group of its own, not root's group
        user = "10001:10001"
        environment = mapOf(
            "ARCHSIM_EXAMPLES_DIR" to imageExamples,
            // every interface of its container, so the published port and the other containers
            // (web calls http://api:8000) reach it; compose publishes the port on 127.0.0.1 only
            "SERVER_ADDRESS" to "0.0.0.0",
        )
    }
    extraDirectories {
        paths {
            path {
                setFrom(rootDir.resolve("examples"))
                into = imageExamples
            }
        }
    }
}

// Jib reads the project while it runs, which the configuration cache does not allow
tasks.withType<com.google.cloud.tools.jib.gradle.JibTask>().configureEach {
    notCompatibleWithConfigurationCache("Jib reads the project at execution time")
}
