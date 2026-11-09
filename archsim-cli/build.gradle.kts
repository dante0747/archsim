// The `archsim` command line: `archsim run` in stage 4, which the benchmark harness runs to simulate
// on the Java engine (ADR-0051); the rest of the Python CLI's commands in stage 5. See README.md.
plugins {
    id("archsim.java-conventions")
    application
}

dependencies {
    implementation(project(":archsim-engine"))
    implementation(libs.picocli)

    // the recorded runs of the Python CLI are compared as the simulator's recorded runs are
    testImplementation(testFixtures(project(":archsim-engine")))
}

application {
    mainClass = "io.archsim.cli.Archsim"
    applicationName = "archsim"
}
