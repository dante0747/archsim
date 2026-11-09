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
    // the simulation baselines' run takes hours: the parity task below runs it
    useJUnitPlatform { excludeTags("parity") }
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
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("parity") }
    // a run of it says something about the simulator, not about these files
    outputs.upToDateWhen { false }
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    val defaults = mapOf("archsim.parity.out" to layout.buildDirectory.dir("parity").get().asFile.absolutePath)
    val forwarded = providers.gradlePropertiesPrefixedBy("archsim.parity.")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { (defaults + forwarded.get()).map { (key, value) -> "-D$key=$value" } },
    )
    // hours of simulation, timed for the report: not under the coverage agent
    extensions.configure<JacocoTaskExtension> { isEnabled = false }
    maxHeapSize = "4g"
    timeout = Duration.ofHours(6)
    testLogging { showStandardStreams = true }
}
