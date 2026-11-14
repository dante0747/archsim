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
    // every kept run checked again takes minutes: the parity task runs it
    useJUnitPlatform { excludeTags("parity") }
    // the parity tests regenerate what the Python harness wrote from benchmarks/results, the
    // engines, the stacks' cpusets, the knee range's fit and the engines' speed, and compare it
    // with the committed pages, charts and accuracy.json: a change to any of them reruns them, as
    // does one to k6's script or to the benchmark workflow's cpusets, which other tests read
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
            "benchmarks/k6/load.js",
            ".github/workflows/benchmark.yml",
        ).map { rootDir.resolve(it) },
    ).withPropertyName("reportSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Every kept run checked again (its inputs derived and anchored again, its steps and its knee
// simulated, its comparison), against the checks the Python harness wrote with the Java engine
// before it was 1.0.0 (results/dev/0.16.0-java/).
//
//   ./gradlew :archsim-benchmarks:parity
tasks.register<Test>("parity") {
    description = "Checks every kept run again and compares the checks with the recorded ones."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("parity") }
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("benchmarks/results")).withPropertyName("results")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    maxHeapSize = "4g"
}
