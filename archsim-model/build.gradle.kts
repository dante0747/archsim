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
    // the rule that keeps a report the same on every platform, which both modules check
    testFixturesApi(libs.archunit)
    testFixturesImplementation(libs.jspecify)

    testImplementation(libs.archunit)
    testImplementation(libs.jqwik)
}

// The price snapshots validation looks documents up in (ADR-0026) are this module's resources, in
// io/archsim/model/pricing/snapshots/. The build lists every file there in an index PriceSnapshots
// reads, so the model ships what the folder holds, no list in code.
val snapshotIndex = tasks.register("snapshotIndex") {
    description = "Lists the price snapshots the model ships, for PriceSnapshots."
    val snapshots = fileTree("src/main/resources/io/archsim/model/pricing/snapshots") { include("*.json") }
    val generated = layout.buildDirectory.dir("generated/snapshot-index")
    inputs.files(snapshots).withPropertyName("snapshots").withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.dir(generated).withPropertyName("index")
    doLast {
        val index = generated.get().file("io/archsim/model/pricing/snapshots/index").asFile
        index.parentFile.mkdirs()
        index.writeText(snapshots.files.map { it.name }.sorted().joinToString("") { "$it\n" })
    }
}

sourceSets.main {
    resources.srcDir(snapshotIndex)
}

tasks.test {
    // the golden files of the frozen Python engine (parity/golden/) and the examples
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}
