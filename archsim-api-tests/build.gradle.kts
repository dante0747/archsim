// Black-box tests of the ArchSim HTTP API. They speak HTTP to a running API (and, where a test
// needs it, SQL to its PostgreSQL database, whose tables are part of the contract). Written
// against the Python API, they now check the Java one that replaced it. See README.md.
//
//   ./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=java      # builds and starts the Java API
//   ./gradlew :archsim-api-tests:apiTest -Parchsim.api.url.local=http://127.0.0.1:8000
plugins {
    id("archsim.java-conventions")
}

dependencies {
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.jupiter.api)
    implementation(libs.jackson.databind)
    implementation(libs.postgresql)
}

testing {
    suites {
        register<JvmTestSuite>("apiTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.jackson.databind)
                implementation(libs.assertj.core)
            }
            targets.all {
                testTask.configure {
                    description = "Runs the black-box API tests against a running (or started) API."
                    // a run of the suite says something about the servers, not about these files
                    outputs.upToDateWhen { false }
                    systemProperty("archsim.repoRoot", rootDir.absolutePath)
                    // the tests of a server without sign-in send a Host of another name, which
                    // Java's HTTP client sets only when allowed to
                    systemProperty("jdk.httpclient.allowRestrictedHeaders", "host")
                    // started servers run and log here: build/api-servers/<profile>.log
                    systemProperty(
                        "archsim.serversDir",
                        layout.buildDirectory.dir("api-servers").get().asFile.absolutePath,
                    )
                    // the Java API runs from archsim-app's jar, built first
                    if (providers.gradleProperty("archsim.api.start").orNull.equals("java", ignoreCase = true)) {
                        dependsOn(":archsim-app:bootJar")
                    }
                    val forwarded = providers.gradlePropertiesPrefixedBy("archsim.")
                    jvmArgumentProviders.add(
                        CommandLineArgumentProvider {
                            forwarded.get().map { (key, value) -> "-D$key=$value" }
                        },
                    )
                }
            }
        }
    }
}

tasks.named<Test>("test") {
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}

// `check` compiles the black-box tests (so Error Prone and NullAway see them) without running
// them: running needs servers.
tasks.named("check") {
    dependsOn(tasks.named("apiTestClasses"))
}
