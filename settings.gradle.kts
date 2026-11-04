// The Java build of ArchSim (see docs/proposals/java-25-port.md). Modules are added as the
// port's stages need them; each one applies the conventions in build-logic/.
pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // downloads a JDK 25 when none is installed (Gradle toolchains)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "archsim"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("archsim-model")
include("archsim-engine")
include("archsim-app")
include("archsim-api-tests")
