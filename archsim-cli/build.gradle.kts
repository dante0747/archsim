// The `archsim` command line: `archsim run` in stage 4, which the benchmark harness runs to simulate
// on the Java engine (ADR-0051); the rest of the Python CLI's commands in stage 5. See README.md.
plugins {
    id("archsim.java-conventions")
    application
}

dependencies {
    implementation(project(":archsim-engine"))
    implementation(libs.picocli)
    // HttpClient (a calibration's Prometheus) logs through SLF4J, which would warn on standard
    // error without a binding: the no-op one, at the version Spring Boot manages (ADR-0057)
    runtimeOnly(libs.slf4j.nop)

    // the recorded runs of the Python CLI are compared as the simulator's recorded runs are, and
    // the JSON its commands printed as the golden files are
    testImplementation(testFixtures(project(":archsim-engine")))
    testImplementation(testFixtures(project(":archsim-model")))
}

application {
    mainClass = "io.archsim.cli.Archsim"
    applicationName = "archsim"
}
