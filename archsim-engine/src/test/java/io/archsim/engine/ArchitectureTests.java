package io.archsim.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import io.archsim.parity.StrictMathRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The engine's architecture, as rules the build checks: it is a plain library over the
 * model (ADR-0012) that calls a server and touches the file system in one package each,
 * and its packages do not depend on each other in circles.
 */
class ArchitectureTests {

	private static final JavaClasses ENGINE = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("io.archsim.engine");

	@Test
	void engineClasses_anyPackage_useNoFrameworkOrDatabase() {
		noClasses().should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework..", "jakarta..", "javax.sql..", "java.sql..", "org.hibernate..")
			.because("the engine is a plain library the app, the CLI and the MCP server share (ADR-0012)")
			.check(ENGINE);
	}

	@Test
	void engineClasses_outsideThePrometheusClient_useNoHttpClient() {
		noClasses().that()
			.resideOutsideOfPackage("io.archsim.engine.calibration.prometheus.http..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.apache.hc..", "java.net.http..")
			.because("the Prometheus client, for a calibration from a live server, is the engine's only HTTP "
					+ "client (ADR-0057)")
			.check(ENGINE);
	}

	@Test
	void engineClasses_outsideTheFilesPackage_useNoFileSystem() {
		noClasses().that()
			.resideOutsideOfPackage("io.archsim.engine.files..")
			.should()
			.dependOnClassesThat(JavaClass.Predicates
				.belongToAnyOf(File.class, FileInputStream.class, FileOutputStream.class, FileReader.class,
						FileWriter.class, RandomAccessFile.class)
				.or(JavaClass.Predicates.resideInAnyPackage("java.nio.file..", "java.nio.channels..")))
			.because("reading and writing the files a caller names, as Python did, is the files package's "
					+ "alone (ADR-0059)")
			.check(ENGINE);
	}

	@Test
	void kernel_anyClass_usesNothingElseOfTheEngineOrModel() {
		classes().that()
			.resideInAPackage("io.archsim.engine.kernel..")
			.should()
			.onlyDependOnClassesThat()
			.resideInAnyPackage("java..", "org.jspecify..", "io.archsim.engine.kernel..")
			.because("the kernel is the bottom layer of the simulator: time, events and queues (ADR-0048)")
			.check(ENGINE);
	}

	@Test
	void engineClasses_anyClass_computeTranscendentalFunctionsWithStrictMath() {
		StrictMathRule.rule().check(ENGINE);
	}

	@Test
	void enginePackages_anyTwo_doNotDependOnEachOther() {
		slices().matching("io.archsim.engine.(**)").should().beFreeOfCycles().check(ENGINE);
	}

}
