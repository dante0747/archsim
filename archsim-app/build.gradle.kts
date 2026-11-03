// The ArchSim application: the HTTP API on Spring Boot 4, one deployable started as `api` or
// `migrate` (ADR-0014), over the same PostgreSQL tables as the Python API (ADR-0008). Stage 2 of
// the Java port; see README.md.
plugins {
    id("archsim.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // one Jackson for the app and the model, whatever Spring Boot's BOM says
    implementation(platform(libs.jackson.bom))
    implementation(project(":archsim-model"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.springdoc.openapi.webmvc.ui)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.archunit)
    // PostgreSQL binaries from Maven Central for the database tests, no Docker (ADR-0033)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    testImplementation(libs.embedded.postgres)
}

tasks.test {
    // the examples, the golden files and the Python engine's price snapshots
    systemProperty("archsim.repoRoot", rootDir.absolutePath)
}

tasks.bootJar {
    // one name whatever the version, so the black-box suite and the image find it
    archiveFileName = "archsim-app.jar"
}
