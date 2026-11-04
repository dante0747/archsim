package io.archsim.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The engine's architecture, as rules the build checks: it is a plain library over the
 * model (ADR-0012), and its packages do not depend on each other in circles.
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
	void enginePackages_anyTwo_doNotDependOnEachOther() {
		slices().matching("io.archsim.engine.(**)").should().beFreeOfCycles().check(ENGINE);
	}

}
