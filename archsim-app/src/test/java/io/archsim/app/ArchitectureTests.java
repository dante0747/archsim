package io.archsim.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The application's architecture, as rules the build checks: one package per feature,
 * each holding its controller, service and repository (ADR-0011), on top of a few shared
 * packages.
 */
class ArchitectureTests {

	private static final JavaClasses APP = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("io.archsim.app");

	/**
	 * What every feature may use: start-up, settings, migrations, the web contract,
	 * access rules, the history, workspace limits and the Python engine service.
	 */
	private static final String[] SHARED = { "io.archsim.app", "io.archsim.app.config..", "io.archsim.app.migration..",
			"io.archsim.app.web..", "io.archsim.app.access..", "io.archsim.app.audit..", "io.archsim.app.limits..",
			"io.archsim.app.engine.." };

	@Test
	void controllers_anyFeature_neverUseARepository() {
		noClasses().that()
			.areAnnotatedWith(RestController.class)
			.should()
			.dependOnClassesThat()
			.areAnnotatedWith(Repository.class)
			.because("a controller speaks HTTP and leaves the feature's work to its service (ADR-0011)")
			.check(APP);
	}

	@Test
	void repositories_anyFeature_areSeenOnlyByTheirOwnFeature() {
		classes().that()
			.areAnnotatedWith(Repository.class)
			.should()
			.notBePublic()
			.because("another feature asks this feature's service, not its tables (ADR-0011)")
			.check(APP);
	}

	@Test
	void sql_anyFeature_isOnlyInRepositories() {
		noClasses().that()
			.areNotAnnotatedWith(Repository.class)
			.should()
			.dependOnClassesThat()
			.areAssignableTo(JdbcClient.class)
			.because("a feature's SQL is in one place, its repository (ADR-0008)")
			.check(APP);
	}

	@Test
	void sharedPackages_always_knowNoFeature() {
		noClasses().that()
			.resideInAnyPackage(SHARED)
			.should()
			.dependOnClassesThat(feature())
			.because("features build on the shared packages, never the other way round")
			.check(APP);
	}

	@Test
	void features_anyTwo_doNotDependOnEachOtherBothWays() {
		slices().matching("io.archsim.app.(*)..").should().beFreeOfCycles().check(APP);
	}

	@Test
	void fields_anyClass_areNotInjected() {
		noFields().should()
			.beAnnotatedWith(Autowired.class)
			.because("dependencies come through the constructor, so they can be final")
			.check(APP);
	}

	private static DescribedPredicate<JavaClass> feature() {
		return JavaClass.Predicates.resideInAPackage("io.archsim.app..")
			.and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(SHARED)))
			.as("a feature's class");
	}

}
