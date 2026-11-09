import java.time.Duration
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

// The engine's deterministic parts: sizing, cost, the instant estimate, the design review,
// confidence, comparisons, exports, importers and calibration fits, over the design model. A plain
// Java library: no Spring, no JDBC (ADR-0012). Stage 3 of the Java port; see README.md.
plugins {
    id("archsim.java-conventions")
    `java-library`
    // the comparison of a Java report with the frozen engine's, shared with archsim-cli's tests
    `java-test-fixtures`
}

dependencies {
    // the engine reads and writes the model's records (ADR-0012)
    api(project(":archsim-model"))
    // the importers read YAML as PyYAML reads it (ADR-0043)
    implementation(libs.snakeyaml)

    // the golden-file reader (ADR-0028)
    testImplementation(testFixtures(project(":archsim-model")))
    testFixturesImplementation(testFixtures(project(":archsim-model")))
    testFixturesImplementation(libs.jspecify)
    testImplementation(libs.archunit)
    testImplementation(libs.jqwik)
}

tasks.test {
    // the golden files of the frozen Python engine (parity/golden/) and the examples
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    // the simulator's measured runs take minutes to hours: the parity and speed tasks run them
    useJUnitPlatform { excludeTags("parity", "speed") }
}

// A measured run of the simulator, over the tests tagged with its name: never up to date (it
// says something about the simulator, not about these files), not under the coverage agent
// (it is timed), and -Parchsim.<tag>.* forwarded as system properties over its defaults.
fun Test.measuredRun(tag: String, defaults: Map<String, String>) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags(tag) }
    outputs.upToDateWhen { false }
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    val forwarded = providers.gradlePropertiesPrefixedBy("archsim.$tag.")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { (defaults + forwarded.get()).map { (key, value) -> "-D$key=$value" } },
    )
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    maxHeapSize = "4g"
    testLogging { showStandardStreams = true }
}

// The Java simulator over the frozen engine's simulation baselines (parity/simulation/,
// ADR-0052), judged by the port plan's bar; it writes build/parity/simulation-parity.json.
// -Parchsim.parity.baselines=DIR judges another recording (a partial one, say), .out=DIR
// writes the report elsewhere (a relative one from the repository's root), .threads=N runs
// N at once and .only=TEMPLATE,... judges those templates alone.
//
//   ./gradlew :archsim-engine:parity -Parchsim.parity.threads=4 -Parchsim.parity.only=queueing-lab
val parity by tasks.registering(Test::class) {
    description = "Simulates the frozen engine's simulation baselines in Java and judges them (hours)."
    measuredRun("parity", mapOf("archsim.parity.out" to layout.buildDirectory.dir("parity").get().asFile.absolutePath))
    timeout = Duration.ofHours(6)
}

// The Java simulator's speed on every template as shipped, timed as scripts/perf.py times the
// frozen engine's (docs/performance.md); it writes build/parity/speed.json. .out=FILE writes
// it elsewhere (a relative one from the repository's root), .repeats=N times N runs (five)
// and .label=NAME names the run.
//
//   ./gradlew :archsim-engine:speed -Parchsim.speed.out=benchmarks/perf/java-port.json
val speed by tasks.registering(Test::class) {
    description = "Times the Java simulator on every template, as scripts/perf.py times the frozen engine."
    measuredRun("speed", mapOf("archsim.speed.out" to layout.buildDirectory.file("parity/speed.json").get().asFile.absolutePath))
    timeout = Duration.ofHours(1)
}
