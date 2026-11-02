// What every Java module of ArchSim gets: Java 25, Spring's formatter, Error Prone with NullAway
// (JSpecify annotations), JUnit with AssertJ, and JaCoCo coverage. `./gradlew check` runs them all
// and fails on any finding; `./gradlew format` fixes the formatting (docs/java-conventions.md).
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    jacoco
    id("io.spring.javaformat")
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "io.archsim"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(version("java"))
    }
}

dependencies {
    "implementation"(lib("jspecify"))
    "errorprone"(lib("errorprone-core"))
    "errorprone"(lib("nullaway"))
    "testImplementation"(platform(lib("junit-bom")))
    "testImplementation"(lib("junit-jupiter"))
    "testImplementation"(lib("assertj-core"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = version("java").toInt()
    // every javac and Error Prone warning fails the build
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "io.archsim")
        option("NullAway:JSpecifyMode", "true")
    }
}

jacoco {
    toolVersion = version("jacoco")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
}
