// The repository's tooling (ADR-0072): the commit messages' check and CI's changed parts, which
// use the JDK alone so that the hook and CI's first job run them from source with Java's source
// launcher, and the monthly price refresh. See README.md.
plugins {
    id("archsim.java-conventions")
}

tasks.test {
    // the CI rules' tests read ci.yml and check every path a rule names
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}
