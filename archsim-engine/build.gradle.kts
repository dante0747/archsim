// The engine's deterministic parts: sizing, cost, the instant estimate, the design review,
// confidence, comparisons, exports, importers and calibration fits, over the design model. A plain
// Java library: no Spring, no JDBC (ADR-0012). Stage 3 of the Java port; see README.md.
plugins {
    id("archsim.java-conventions")
    `java-library`
}

dependencies {
    // the engine reads and writes the model's records (ADR-0012)
    api(project(":archsim-model"))
    // the importers read YAML as PyYAML reads it (ADR-0043)
    implementation(libs.snakeyaml)

    // the golden-file reader (ADR-0028)
    testImplementation(testFixtures(project(":archsim-model")))
    testImplementation(libs.archunit)
    testImplementation(libs.jqwik)
}

tasks.test {
    // the golden files of the frozen Python engine (parity/golden/) and the examples
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}
