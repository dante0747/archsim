package io.archsim.model;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The model's architecture, as rules the build checks: it is a plain library (ADR-0012), and its
 * packages build on each other in one direction.
 */
class ArchitectureTest {

    private static final JavaClasses MODEL = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.archsim.model");

    @Test
    void the_model_uses_no_framework_and_no_database() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..", "jakarta..", "javax.sql..", "java.sql..", "org.hibernate..")
                .because("the model is a plain library the app, the CLI and the MCP server share (ADR-0012)")
                .check(MODEL);
    }

    @Test
    void the_model_never_uses_the_engine() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.archsim.engine..")
                .because("the engine builds on the model, not the other way round (ADR-0012)")
                .check(MODEL);
    }

    @Test
    void no_two_packages_depend_on_each_other() {
        slices().matching("io.archsim.model.(**)").should().beFreeOfCycles().check(MODEL);
    }

    @Test
    void each_package_uses_only_those_below_it() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
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
                .definedBy("io.archsim.model.text..")
                .whereLayer("Validation")
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
}
