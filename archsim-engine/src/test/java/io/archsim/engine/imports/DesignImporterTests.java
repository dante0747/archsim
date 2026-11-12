package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.NullNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Importing a design, read and validated. The golden files check every recorded import;
 * these check what they record none of.
 */
class DesignImporterTests {

	private final DesignImporter importer = new DesignImporter(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void importDesign_designThatCannotBeRead_throwsItsIssues() {
		String manifests = "kind: Deployment\nmetadata: {name: a, labels: {app.kubernetes.io/part-of: [x]}}\n";

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> this.importer.importDesign(ImportFormat.K8S, manifests, null))
			.extracting(DocumentInvalidException::issues)
			.satisfies((issues) -> assertThat(issues).extracting(Issue::code).containsExactly("document.schema"));
	}

	@Test
	void importDesign_balancerWithNoTarget_reportsTheMissingLink() {
		ImportedDesign imported = this.importer.importDesign(ImportFormat.COMPOSE,
				"services:\n  web:\n    image: nginx\n", null);

		assertThat(imported.report().issues()).extracting((issue) -> issue.path("code").stringValue())
			.containsExactly("port.missing_link");
	}

	/**
	 * Python's {@code float()} reads a figure of any length, past the largest float as
	 * infinity, which the design's JSON writes as {@code null}, as pydantic's JSON mode
	 * wrote a float a component's settings held.
	 */
	@Test
	void importDesign_cpuFigureOfTenThousandDigits_isNullWithTheLimitIssue() {
		String compose = """
				services:
				  api:
				    image: node:20
				    deploy:
				      resources:
				        limits:
				          cpus: "%s"
				""".formatted("1".repeat(10_000));

		ImportedDesign imported = this.importer.importDesign(ImportFormat.COMPOSE, compose, null);

		assertThat(imported.document().at("/architecture/nodes/0/config/cpuCores")).isEqualTo(NullNode.getInstance());
		assertThat(imported.report().issues()).extracting((issue) -> issue.path("message").stringValue())
			.containsExactly("api: 'cpuCores' input should be less than or equal to 512");
	}

}
