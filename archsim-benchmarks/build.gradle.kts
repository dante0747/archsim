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
    // the parity tests regenerate what the Python harness wrote from benchmarks/results, the
    // engines, the stacks' cpusets, the knee range's fit and the engines' speed, and compare it
    // with the committed pages, charts and accuracy.json: a change to any of them reruns them
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("benchmarks/results")).withPropertyName("results")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootDir.resolve("docs/validation")).withPropertyName("validationFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        listOf(
            "benchmarks/engines.json",
            "benchmarks/compose.yaml",
            "benchmarks/knee-range.json",
            "docs/validation.md",
            "apps/web/src/data/accuracy.json",
            "benchmarks/perf/python-0.16.0.json",
            "benchmarks/perf/java-port.json",
        ).map { rootDir.resolve(it) },
    ).withPropertyName("reportSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
