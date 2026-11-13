package io.archsim.benchmarks.range;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.inputs.ModelDistributions;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The inputs the range varies, each scaled as a whole, and the ones it leaves as
 * calibrated.
 */
class RangeInputsTests {

	static final String INPUTS = """
			{"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.17, "p95": 0.46}, "how": ""},
			 "clientLinkLatencyMs": {"value": 0.005, "how": ""},
			 "commitFlushMs": {"value": {"kind": "lognormal", "median": 0.8, "p95": 1.6}, "how": ""},
			 "dbCpuShare": {"value": 0.9, "how": ""},
			 "readServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.2, "p95": 0.5}, "how": ""},
			 "anchor": {"rateRps": 1674.0},
			 "measuredLowLoad": {"rateRps": 1674.0}}""";

	@Test
	void scaled_aLognormal_movesItsMedianAndP95() {
		JsonNode scaled = RangeInputs.scaled(JsonText.read("{\"kind\": \"lognormal\", \"median\": 0.2, \"p95\": 0.5}"),
				2.0);

		assertThat(scaled).isEqualTo(JsonText.read("{\"kind\": \"lognormal\", \"median\": 0.4, \"p95\": 1.0}"));
	}

	@Test
	void scaled_anExponential_movesItsMean() {
		JsonNode scaled = RangeInputs.scaled(JsonText.read("{\"kind\": \"exponential\", \"mean\": 0.04}"), 0.5);

		assertThat(scaled).isEqualTo(JsonText.read("{\"kind\": \"exponential\", \"mean\": 0.02}"));
	}

	@Test
	void scaled_aConstant_movesItsValue() {
		JsonNode scaled = RangeInputs.scaled(JsonText.read("{\"kind\": \"constant\", \"value\": 3.0}"), 2.0);

		assertThat(scaled.get("value").doubleValue()).isEqualTo(6.0);
	}

	@Test
	void scaled_anEmpirical_movesEachQuantile() {
		JsonNode scaled = RangeInputs
			.scaled(JsonText.read("{\"kind\": \"empirical\", \"quantiles\": [[0, 1.0], [1, 2.0]]}"), 3.0);

		assertThat(scaled.get("quantiles")).isEqualTo(JsonText.read("[[0, 3.0], [1, 6.0]]"));
	}

	@Test
	void scaled_aNumberOrABoolean_isTheNumberTimesTheFactorOrTheBoolean() {
		assertThat(RangeInputs.scaled(JsonText.read("0.25"), 2.0).doubleValue()).isEqualTo(0.5);
		assertThat(RangeInputs.scaled(JsonText.read("true"), 2.0).booleanValue()).isTrue();
	}

	// a distribution's mean, as the engine samples it
	@Test
	void scale_aDistribution_isItsMean() {
		JsonNode lognormal = JsonText.read("{\"kind\": \"lognormal\", \"median\": 0.2, \"p95\": 0.5}");

		assertThat(RangeInputs.scale(lognormal)).isCloseTo(ModelDistributions.mean(lognormal), within(1e-12));
	}

	@Test
	void scale_aNumberOrNone_isItselfOrNone() {
		assertThat(RangeInputs.scale(JsonText.read("0.4"))).isEqualTo(0.4);
		assertThat(RangeInputs.scale(JsonText.read("null"))).isNull();
	}

	// a share of the query time: never above 1
	@Test
	void perturbed_theCpuShare_isAtMostOne() {
		ObjectNode up = RangeInputs.perturbed(JsonText.read(INPUTS), "dbCpuShare", 1.5);

		assertThat(up.at("/dbCpuShare/value").doubleValue()).isEqualTo(1.0);
	}

	@Test
	void perturbed_anInput_isACopyWithItMoved() {
		JsonNode inputs = JsonText.read(INPUTS);

		ObjectNode up = RangeInputs.perturbed(inputs, "serviceTimeMs", 2.0);

		assertThat(up.at("/serviceTimeMs/value/median").doubleValue()).isCloseTo(0.34, within(1e-12));
		assertThat(inputs.at("/serviceTimeMs/value/median").doubleValue()).isEqualTo(0.17);
	}

	@Test
	void keys_aDesignsInputs_areTheVariedOnesWithAScaleInTheirOrder() {
		assertThat(RangeInputs.keys(JsonText.read(INPUTS))).containsExactly("serviceTimeMs", "clientLinkLatencyMs",
				"readServiceTimeMs", "commitFlushMs", "dbCpuShare");
	}

	// a new input must be put in one of them: checked on every development check's inputs
	@Test
	void lists_everyInputOfEveryDevelopmentCheck_isVariedOrFixed() throws IOException {
		Set<String> keys = new HashSet<>(JsonText.read(INPUTS).propertyNames());
		try (Stream<Path> checks = Files.walk(RepositoryLayout.current().results().resolve("dev"), 3)) {
			checks.filter((file) -> file.toString().endsWith(".json"))
				.forEach((file) -> keys.addAll(ResultFiles.read(file).orElseThrow().get("inputs").propertyNames()));
		}

		keys.removeAll(RangeInputs.VARIED);
		keys.removeAll(RangeInputs.FIXED);

		assertThat(keys).as("put these inputs in RangeInputs.VARIED or RangeInputs.FIXED").isEmpty();
	}

	@Test
	void lists_theVariedAndTheFixed_shareNoInput() {
		assertThat(RangeInputs.VARIED).doesNotContainAnyElementsOf(RangeInputs.FIXED);
	}

}
