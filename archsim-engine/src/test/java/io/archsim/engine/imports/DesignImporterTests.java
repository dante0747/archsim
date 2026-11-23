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

	private static final String AUTOSCALER_ON_MEMORY = """
			apiVersion: apps/v1
			kind: Deployment
			metadata: {name: api}
			spec:
			  template:
			    spec:
			      containers:
			        - {name: api, image: node:20}
			---
			apiVersion: autoscaling/v2
			kind: HorizontalPodAutoscaler
			metadata: {name: api}
			spec:
			  scaleTargetRef: {name: api}
			  minReplicas: 2
			  maxReplicas: 5
			  metrics:
			    - type: Resource
			      resource: {name: memory, target: {type: Utilization, averageUtilization: 70}}
			""";

	private final DesignImporter importer = new DesignImporter(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void importDesign_autoscalerOnMemory_saysItsMetricIsIgnoredForWhatArchSimScalesOn() {
		ImportedDesign imported = this.importer.importDesign(ImportFormat.K8S, AUTOSCALER_ON_MEMORY, null);

		assertThat(imported.report().ignored()).contains("HorizontalPodAutoscaler api: memory metric "
				+ "(ArchSim scales on worker utilization or, with a CPU size, CPU utilization if higher)");
	}

	@Test
	void importDesign_autoscalerWithoutACpuTarget_guessesTheDefaultTargetWithoutCallingItCpuOnly() {
		ImportedDesign imported = this.importer.importDesign(ImportFormat.K8S, AUTOSCALER_ON_MEMORY, null);

		assertThat(imported.report().guessed()).contains(
				"HorizontalPodAutoscaler api has no CPU utilization target: api scales at ArchSim's default target");
	}

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
