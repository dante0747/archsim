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
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.starter.security.oauth2.client)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.archunit)
    // the black-box suite's stand-in GitHub, for the sign-in tests
    testImplementation(project(":archsim-api-tests"))
    // PostgreSQL binaries from Maven Central for the database tests, no Docker (ADR-0033)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
}

tasks.test {
    // the examples, the golden files and the test fixtures (parity/fixtures)
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    // the worker's command lines, which WorkerCommandLineTests checks, and the compose files'
    // images, which ComposeFileTests checks: a change to them alone runs the tests again rather
    // than taking their result from the build cache
    inputs.files(
        listOf("docker-compose.yml", "docker-compose.local.yml", "apps/web/e2e/api-server.mjs").map { rootDir.resolve(it) },
        buildFile,
    ).withPropertyName("deploymentFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}

// The libraries the engine and the CLI take from the catalog are pinned at the versions Spring
// Boot manages (ADR-0043, ADR-0057): the engine and the CLI have no BOM, and the CLI ships
// alone, with slf4j-nop where the app has Boot's logging. A Boot upgrade that moves one of them
// fails the build here, until the catalog follows.
val bootManaged = configurations.create("bootManaged") {
    isCanBeConsumed = false
    description = "The versions Spring Boot manages of the libraries pinned in the catalog."
}
val pinnedAtBootsVersion = listOf(libs.snakeyaml, libs.httpclient5, libs.slf4j.nop).map { it.get() }

dependencies {
    bootManaged(platform(libs.spring.boot.dependencies))
    pinnedAtBootsVersion.forEach { bootManaged(it.module.toString()) }
}

val checkBootManagedVersions = tasks.register("checkBootManagedVersions") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when a library pinned in the catalog is not at the version Spring Boot manages."
    val pinned = pinnedAtBootsVersion.associate { it.module.name to it.version.orEmpty() }
    val managed = bootManaged.incoming.resolutionResult.rootComponent.map { root ->
        root.dependencies.filterIsInstance<ResolvedDependencyResult>()
            .mapNotNull { it.selected.moduleVersion }
            .filter { it.name in pinned }
            .associate { it.name to it.version }
    }
    inputs.property("pinned", pinned)
    inputs.property("managed", managed)
    doLast {
        val differing = pinned.filter { (name, version) -> managed.get()[name] != version }
        check(differing.isEmpty()) {
            "gradle/libs.versions.toml pins ${differing.keys} at ${differing.values}, but Spring Boot manages " +
                "${differing.keys.associateWith { managed.get()[it] }}: set the catalog to Boot's versions"
        }
    }
}

tasks.check {
    dependsOn(checkBootManagedVersions)
}

tasks.bootJar {
    // one name whatever the version, so the black-box suite and the image find it
    archiveFileName = "archsim-app.jar"
}

// The image compose runs as `migrate`, `api` and `worker` (ADR-0014). It carries the examples, as
// the Python image did. ./gradlew :archsim-app:jibDockerBuild (make image) builds it into the local
// Docker as archsim-app:local, for this machine's processor, which docker-compose.local.yml runs. A
// release pushes it to GHCR for both processors with Jib's own properties, which override these
// (.github/workflows/release.yml, ADR-0077):
//
//   ./gradlew :archsim-app:jib -Djib.to.image=ghcr.io/dante0747/archsim-app:<version> \
//       -Djib.from.platforms=linux/amd64,linux/arm64
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
        // every role exits when its heap runs out rather than limp on: a worker's runs are then
        // requeued (ADR-0053); compose gives the worker its heap
        jvmFlags = listOf("-XX:+ExitOnOutOfMemoryError")
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
        // the labels the release workflow gives archsim-web; the source links GHCR's package to
        // the repository, and the description replaces the Ubuntu base image's
        labels = mapOf(
            "org.opencontainers.image.title" to "archsim-app",
            "org.opencontainers.image.description" to
                "ArchSim's API, simulation worker and database migration, started as api, worker, migrate or seed",
            "org.opencontainers.image.version" to project.version.toString(),
            "org.opencontainers.image.source" to "https://github.com/dante0747/archsim",
            "org.opencontainers.image.licenses" to "AGPL-3.0-only",
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
