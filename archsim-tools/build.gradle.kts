// The repository's tooling (ADR-0072): the commit messages' check and CI's changed parts, which
// use the JDK alone so that the hook and CI's first job run them from source with Java's source
// launcher, and the price refresh, which Gradle runs from the repository's root. See README.md.
//
//   ./gradlew :archsim-tools:run --args="prices aws --region us-east-1"
plugins {
    id("archsim.java-conventions")
    application
}

dependencies {
    // the price refresh rounds, reads and writes JSON as scripts/prices did in Python
    implementation(project(":archsim-engine"))
    implementation(libs.picocli)
    // the archsim-check action's test reads the step it runs from action.yml
    testImplementation(libs.snakeyaml)
}

application {
    mainClass = "io.archsim.tools.Tools"
    applicationName = "archsim-tools"
}

// the refresh writes among archsim-model's snapshots and caches its downloads in .cache/, both
// from the repository's root, as scripts/prices did
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

tasks.test {
    // the CI rules' tests read ci.yml and check every path a rule names; the hook's tests run it
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    inputs.file(rootDir.resolve(".githooks/commit-msg")).withPropertyName("commitMsgHook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
