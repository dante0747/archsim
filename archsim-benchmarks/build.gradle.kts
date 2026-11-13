// The accuracy benchmarks' harness: calibrate a stack at low load, predict it with the engine,
// measure it with k6, compare, and write the validation page from the results. Not shipped; its
// data (the stacks, k6, compose.yaml and the recorded results) stays in benchmarks/ (ADR-0068).
// Run from the repository's root (ADR-0069):
//
//   ./gradlew :archsim-benchmarks:run --args="compare"
plugins {
    id("archsim.java-conventions")
    application
}

dependencies {
    // the engine simulates the designs and fits the inputs, in process (ADR-0068)
    implementation(project(":archsim-engine"))
    implementation(libs.picocli)
}

application {
    mainClass = "io.archsim.benchmarks.Benchmarks"
    applicationName = "archsim-benchmarks"
    // the same heap every time, and an end rather than a half-written result when it runs out:
    // an overloaded ladder step holds up to 100,000 queued requests (ADR-0069)
    applicationDefaultJvmArgs = listOf("-Xmx4g", "-XX:+ExitOnOutOfMemoryError")
}

// the harness reads and writes benchmarks/ and docs/ from the repository's root
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}

tasks.test {
    // the parity tests regenerate what the Python harness wrote from benchmarks/results
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("benchmarks/results")).withPropertyName("results")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
