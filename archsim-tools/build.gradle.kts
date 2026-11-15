// The repository's tooling (ADR-0072): the commit messages' check, CI's changed parts and the
// release's check, which use the JDK alone so that the hook and the workflows run them from source
// with Java's source launcher, and the price refresh, which Gradle runs from the repository's root.
// See README.md.
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
    // the CI rules' tests read ci.yml, the action's test runs its step from action.yml, the
    // hook's tests run the hook, and the release's tests check release.yml against the version
    // and compose's images, so a change to any of them reruns them. The rules' tests also check
    // that every path a rule names exists, which depends on the whole tree: a path deleted
    // elsewhere is caught on the next full run.
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
    inputs.file(rootDir.resolve(".githooks/commit-msg")).withPropertyName("commitMsgHook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve(".github/workflows/ci.yml")).withPropertyName("ciWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootDir.resolve(".github/actions/archsim-check")).withPropertyName("archsimCheckAction")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(listOf(".github/workflows/release.yml", "gradle.properties", "docker-compose.yml").map {
        rootDir.resolve(it)
    }).withPropertyName("release").withPathSensitivity(PathSensitivity.RELATIVE)
}
