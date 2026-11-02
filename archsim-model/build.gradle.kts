// The design model: the design document as Java records, its upgrades from schema 1.0 to 1.7,
// validation whose messages match the Python engine's word for word, and the document's JSON
// Schema. A plain Java library: no Spring, no JDBC (ADR-0012). See README.md.
plugins {
    id("archsim.java-conventions")
    `java-library`
    // the golden-file reader, shared with the modules of later stages (ADR-0028)
    `java-test-fixtures`
}

dependencies {
    // documents come in and go out as Jackson trees (ADR-0022)
    api(libs.jackson.databind)

    testFixturesApi(libs.jackson.databind)
    testFixturesApi(libs.assertj.core)
    testFixturesImplementation(libs.jspecify)

    testImplementation(libs.archunit)
    testImplementation(libs.jqwik)
}

// The price snapshots validation looks documents up in (ADR-0026). The Python engine ships them;
// while it exists its folder stays the one copy, so the two never disagree. When it is deleted,
// the files move to src/main/resources/io/archsim/model/pricing/snapshots/.
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("packages/engine/src/archsim_engine/pricing")) {
        include("*.json")
        into("io/archsim/model/pricing/snapshots")
    }
}

tasks.test {
    // the golden files of the frozen Python engine (parity/golden/) and the examples
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}
