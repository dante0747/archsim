package io.archsim.engine.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import io.archsim.engine.review.ReviewRule;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The analysis of a design in the cases the golden files do not reach: confidence that is
 * high or medium, held down by a bad calibration fit or an experimental technology, or
 * weighed by the inputs alone; quick checks of every kind; a scenario with a calibration
 * record; a document that cannot be read. Each case ({@code analysis/cases.json}, written
 * by {@code scripts/export_analysis_cases.py}) has the analysis the Python engine 0.16.0
 * gives, or the error it raises.
 */
class DesignAnalyzerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DesignAnalyzer analyzer = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> analyze_eachCase_isThePythonEnginesAnalysis() throws IOException {
		return cases().filter((entry) -> entry.path("analysis").has("output"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertAnalysis(entry)));
	}

	@TestFactory
	Stream<DynamicTest> analyze_aDocumentThePythonEngineCannotRead_isInvalidWithItsIssues() throws IOException {
		return cases().filter((entry) -> entry.path("analysis").has("error"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertRefusal(entry)));
	}

	@Test
	void analyze_aPartWithALoadAndNoCpu_isAnalyzedWithoutItsCpuCheck() {
		JsonNode design = JSON.readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "No CPU"},
				 "architecture": {"nodes": [{"id": "clients", "type": "traffic_source"},
				                            {"id": "api", "type": "service",
				                             "config": {"cpuCores": 0, "replicas": 2,
				                                        "serviceTimeMs": {"kind": "constant", "value": 10}}}],
				                  "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": {"rps": 100}}""");

		DesignAnalysis analysis = this.analyzer.analyze(design, null);

		assertThat(analysis.review()).isNotEmpty().noneMatch((finding) -> finding.rule() == ReviewRule.CPU_CAPACITY);
		assertThat(analysis.estimate().parts()).isNotEmpty();
	}

	private void assertAnalysis(JsonNode entry) {
		DesignAnalysis analysis = this.analyzer.analyze(entry.path("design"), scenario(entry));

		assertThat(JsonDiff.differences(entry.path("analysis").path("output"), analysis.toJson())).isEmpty();
	}

	private void assertRefusal(JsonNode entry) {
		DocumentInvalidException refusal = catchThrowableOfType(DocumentInvalidException.class,
				() -> this.analyzer.analyze(entry.path("design"), scenario(entry)));

		JsonNode error = entry.path("analysis").path("error");
		assertThat(error.path("type").asString()).isEqualTo("DocumentInvalidError");
		assertThat(refusal).hasMessage(error.path("message").asString());
		ArrayNode issues = JSON.createArrayNode();
		refusal.issues().stream().map(Issue::toJson).forEach(issues::add);
		assertThat(JsonDiff.differences(error.path("issues"), issues)).isEmpty();
	}

	private static String scenario(JsonNode entry) {
		return entry.path("scenarioId").asString(null);
	}

	private static Stream<JsonNode> cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				DesignAnalyzerTests.class.getResourceAsStream("/io/archsim/engine/analysis/cases.json"),
				"cases.json")) {
			return JSON.readTree(in).path("cases").valueStream().toList().stream();
		}
	}

}
