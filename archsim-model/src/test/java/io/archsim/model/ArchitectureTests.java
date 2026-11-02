package io.archsim.model;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The model's architecture, as rules the build checks: it is a plain library (ADR-0012),
 * and its packages build on each other in one direction.
 */
class ArchitectureTests {

	private static final JavaClasses MODEL = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("io.archsim.model");

	@Test
	void modelClasses_anyPackage_useNoFrameworkOrDatabase() {
		noClasses().should()
			.dependOnClassesThat()
			.resideInAnyPackage("org.springframework..", "jakarta..", "javax.sql..", "java.sql..", "org.hibernate..")
			.because("the model is a plain library the app, the CLI and the MCP server share (ADR-0012)")
			.check(MODEL);
	}

	@Test
	void modelClasses_anyPackage_neverUseTheEngine() {
		noClasses().should()
			.dependOnClassesThat()
			.resideInAPackage("io.archsim.engine..")
			.because("the engine builds on the model, not the other way round (ADR-0012)")
			.check(MODEL);
	}

	@Test
	void modelPackages_anyTwo_doNotDependOnEachOther() {
		slices().matching("io.archsim.model.(**)").should().beFreeOfCycles().check(MODEL);
	}

	@Test
	void modelPackages_eachLayer_useOnlyTheLayersBelow() {
		modelLayers().whereLayer("Validation")
			.mayOnlyAccessLayers("Components", "Pricing", "Issues", "Document", "Codec", "Text")
			.whereLayer("Scenarios")
			.mayOnlyAccessLayers("Issues", "Document", "Text")
			.whereLayer("Components")
			.mayOnlyAccessLayers("Issues", "Document", "Codec", "Text")
			.whereLayer("Pricing")
			.mayOnlyAccessLayers("Codec", "Text")
			.whereLayer("Upgrades")
			.mayOnlyAccessLayers("Document", "Text")
			.whereLayer("Issues")
			.mayOnlyAccessLayers("Codec", "Text")
			.whereLayer("Document")
			.mayOnlyAccessLayers("Codec", "Text")
			.whereLayer("Codec")
			.mayOnlyAccessLayers("Text")
			.whereLayer("Text")
			.mayNotAccessAnyLayer()
			.check(MODEL);
	}

	/**
	 * The model's packages as layers, from the API at the top to the text conventions at
	 * the bottom.
	 */
	private static LayeredArchitecture modelLayers() {
		return layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Api")
			.definedBy("io.archsim.model")
			.layer("Validation")
			.definedBy("io.archsim.model.validation..")
			.layer("Scenarios")
			.definedBy("io.archsim.model.scenario..")
			.layer("Components")
			.definedBy("io.archsim.model.component..")
			.layer("Pricing")
			.definedBy("io.archsim.model.pricing..")
			.layer("Upgrades")
			.definedBy("io.archsim.model.upgrade..")
			.layer("Issues")
			.definedBy("io.archsim.model.issue..")
			.layer("Document")
			.definedBy("io.archsim.model.document..", "io.archsim.model.distribution..")
			.layer("Codec")
			.definedBy("io.archsim.model.codec..")
			.layer("Text")
			.definedBy("io.archsim.model.text..");
	}

}
