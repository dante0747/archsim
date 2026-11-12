package io.archsim.engine.run;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.JsonDiff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Preparing a run, against the Python engine 0.16.0's {@code prepare_run} as the engine
 * service answered with it: the design to store, its scenario, the seed and the quick
 * check; or the refusal of a seed out of range, a run over its limit, and a design that
 * cannot be read or simulated ({@code analysis/cases.json}, recorded from the frozen
 * engine by {@code scripts/export_analysis_cases.py}, since deleted).
 */
class RunPreparerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunPreparer preparer = new RunPreparer(StandardComponents.registry(), PriceSnapshots.shipped());

	@TestFactory
	Stream<DynamicTest> prepare_eachCase_isTheRunThePythonEnginePrepares() throws IOException {
		return cases().filter((entry) -> entry.path("prepared").has("output"))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertPrepared(entry)));
	}

	@TestFactory
	Stream<DynamicTest> prepare_aDesignThePythonEngineCannotSimulate_isInvalidWithItsIssues() throws IOException {
		return cases().filter((entry) -> "DocumentInvalidError".equals(type(entry)))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertInvalid(entry)));
	}

	@TestFactory
	Stream<DynamicTest> prepare_aRunOverItsLimit_isRefusedInThePythonEnginesWords() throws IOException {
		return cases().filter((entry) -> "SimulationLimitError".equals(type(entry)))
			.map((entry) -> DynamicTest.dynamicTest(entry.path("name").asString(), () -> assertOverLimit(entry)));
	}

	private void assertPrepared(JsonNode entry) {
		PreparedRun run = prepare(entry);

		ObjectNode answer = JSON.createObjectNode()
			.put("scenarioId", run.scenarioId())
			.put("scenarioName", run.scenarioName())
			.put("seed", run.seed());
		answer.set("document", DesignDocuments.toJson(run.document()));
		answer.set("quickCheck", RecordCodec.write(run.quickCheck()));
		assertThat(JsonDiff.differences(entry.path("prepared").path("output"), answer)).isEmpty();
	}

	private void assertInvalid(JsonNode entry) {
		DocumentInvalidException refusal = catchThrowableOfType(DocumentInvalidException.class, () -> prepare(entry));

		JsonNode error = entry.path("prepared").path("error");
		assertThat(refusal).hasMessage(error.path("message").asString());
		ArrayNode issues = JSON.createArrayNode();
		refusal.issues().stream().map(Issue::toJson).forEach(issues::add);
		assertThat(JsonDiff.differences(error.path("issues"), issues)).isEmpty();
	}

	private void assertOverLimit(JsonNode entry) {
		SimulationLimitException refusal = catchThrowableOfType(SimulationLimitException.class, () -> prepare(entry));

		assertThat(refusal).hasMessage(entry.path("prepared").path("error").path("message").asString());
	}

	private PreparedRun prepare(JsonNode entry) {
		JsonNode seed = entry.path("seed");
		return this.preparer.prepare(entry.path("design"), entry.path("scenarioId").asString(null),
				SimulationLimits.DEFAULT, seed.isNumber() ? seed.longValue() : null);
	}

	private static String type(JsonNode entry) {
		return entry.path("prepared").path("error").path("type").asString("");
	}

	private static Stream<JsonNode> cases() throws IOException {
		try (InputStream in = Objects.requireNonNull(
				RunPreparerTests.class.getResourceAsStream("/io/archsim/engine/analysis/cases.json"), "cases.json")) {
			return JSON.readTree(in).path("cases").valueStream().toList().stream();
		}
	}

}
