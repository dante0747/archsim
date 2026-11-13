package io.archsim.benchmarks.inputs;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.calibration.resources.MemoryFits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Each service's memory at idle and per req/s, fitted to its container's working set over
 * the staircase and at rest (W24). Calibrations without readings keep ArchSim's defaults.
 */
class MemoryInputsTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<Double> RATES = List.of(50.0, 400.0, 1000.0, 1600.0, 1600.0, 1000.0, 400.0, 50.0);

	@Test
	void of_aStaircaseAndAnIdlePass_fitsTheServicesMemoryAtIdleAndPerRate() {
		ObjectNode passes = NODES.objectNode();
		passes.set("staircase", staircase("api", RATES, (rate) -> 40.0 + 0.05 * rate));
		passes.putObject("idle").putObject("workingSetMiB").put("api", 40.0);

		ObjectNode inputs = MemoryInputs.of(PG_MIXED, passes);

		assertThat(inputs.propertyNames()).containsExactly("serviceMemoryBaselineMb", "serviceMemoryPerRpsMb",
				"serviceMemoryPerRequestMb");
		assertThat(inputs.at("/serviceMemoryBaselineMb/value").doubleValue()).isCloseTo(40.0, within(1e-3));
		assertThat(inputs.at("/serviceMemoryPerRpsMb/value").doubleValue()).isCloseTo(0.05, within(1e-6));
		assertThat(inputs.at("/serviceMemoryPerRequestMb/value").doubleValue())
			.isEqualTo(MemoryFits.MIN_PER_REQUEST_MB);
	}

	@Test
	void of_aStaircaseAndAnIdlePass_saysWhatItFitted() {
		ObjectNode passes = NODES.objectNode();
		passes.set("staircase", staircase("api", RATES, (rate) -> 40.0 + 0.05 * rate));
		passes.putObject("idle").putObject("workingSetMiB").put("api", 40.0);

		ObjectNode inputs = MemoryInputs.of(PG_MIXED, passes);

		assertThat(inputs.at("/serviceMemoryBaselineMb/how").stringValue())
			.contains("9 points (at rest and 8 passes, 50 to 1,600 req/s)")
			.contains("40.0 MiB + 0.0500 MiB per req/s");
	}

	@Test
	void of_chain_fitsTheGatewayAndTheApiApart() {
		ArrayNode staircase = staircase("gateway", List.of(50.0, 500.0, 1000.0), (rate) -> 30.0 + 0.02 * rate);
		staircase.forEach((pass) -> ((ObjectNode) pass.at("/resources/containers")).putObject("chain-api")
			.put("workingSetMiB", 50.0 + 0.04 * pass.get("rateRps").doubleValue()));
		ObjectNode passes = NODES.objectNode().set("staircase", staircase);

		ObjectNode inputs = MemoryInputs.of(Experiments.standard().get("chain"), passes);

		assertThat(inputs.at("/serviceMemoryBaselineMb/value").doubleValue()).isCloseTo(30.0, within(1e-3));
		assertThat(inputs.at("/apiMemoryBaselineMb/value").doubleValue()).isCloseTo(50.0, within(1e-3));
		assertThat(inputs.at("/apiMemoryPerRpsMb/value").doubleValue()).isCloseTo(0.04, within(1e-6));
	}

	// every kept calibration before the v6 campaign predates W24: no working set in
	// its passes, so no memory inputs, and its design keeps ArchSim's defaults exactly
	@ParameterizedTest
	@ValueSource(
			strings = { "pg-mixed/run-10", "pg-hot-row/run-8", "cache-aside/run-5", "kafka/run-5", "pg-mixed/run-6" })
	void of_aCalibrationWithoutReadings_fitsNothing(String folder) {
		JsonNode calibration = calibration(folder);
		Experiment experiment = Experiments.standard().get(folder.substring(0, folder.indexOf('/')));

		List<ObjectNode> inputs = calibration.get("replicates")
			.valueStream()
			.map((replicate) -> MemoryInputs.of(experiment, replicateSet(replicate, calibration)))
			.toList();

		assertThat(inputs).isNotEmpty().allSatisfy((memory) -> assertThat(memory.isEmpty()).isTrue());
	}

	@Test
	void of_onePoint_fitsNothing() {
		ObjectNode passes = NODES.objectNode()
			.set("staircase", staircase("api", List.of(400.0, 400.0), (rate) -> 60.0));

		assertThat(MemoryInputs.of(PG_MIXED, passes).isEmpty()).isTrue();
	}

	// a staircase that never rose (the host was busy) fits nothing either
	@Test
	void of_restAndOneRate_fitsNothing() {
		ObjectNode passes = NODES.objectNode()
			.set("staircase", staircase("api", List.of(400.0, 400.0), (rate) -> 60.0));
		passes.putObject("idle").putObject("workingSetMiB").put("api", 24.0);

		assertThat(MemoryInputs.of(PG_MIXED, passes).isEmpty()).isTrue();
	}

	// the 31 Oct trial, calibration passes only: every replicate fits
	// the same staircase, so the trial's own inputs are this fit
	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka" })
	void of_theW24Trial_isTheTrialsOwnMemoryInputs(String experiment) {
		JsonNode trial = trial(experiment);

		ObjectNode inputs = MemoryInputs.of(Experiments.standard().get(experiment), trialPasses(trial));

		ObjectNode recorded = NODES.objectNode();
		trial.get("inputs")
			.properties()
			.stream()
			.filter((input) -> input.getKey().contains("Memory"))
			.forEach((input) -> recorded.set(input.getKey(), input.getValue().get("value")));
		ObjectNode fitted = NODES.objectNode();
		inputs.properties().forEach((input) -> fitted.set(input.getKey(), input.getValue().get("value")));
		assertThat(fitted).isEqualTo(recorded);
	}

	// against the baseline campaign's latest run's steps at or below both
	// knees, predicted with ArchSim's defaults (256 MiB + 2 MiB per request
	// held, 59% to 485% high): within 70% everywhere, and low at the top
	// step (-25% to -65%), where V8's heap grows faster than the rate a
	// line through a staircase that stops at a third of the knee follows
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "pg-mixed, api, pg-mixed/run-10", "pg-hot-row, api, pg-hot-row/run-8",
			"cache-aside, api, cache-aside/run-5", "kafka, producer, kafka/run-5" })
	void of_theW24Trial_isCloserToTheMeasuredMemoryThanTheDefaults(String experiment, String container,
			String baseline) {
		ObjectNode inputs = MemoryInputs.of(Experiments.standard().get(experiment), trialPasses(trial(experiment)));
		JsonNode comparison = ResultFiles
			.read(RepositoryLayout.current().results().resolve(baseline).resolve("comparison.json"))
			.orElseThrow();
		List<JsonNode> cells = Knees.atOrBelowBothKnees(comparison.get("rows").valueStream().toList(),
				comparison.get("measuredKnee"), comparison.get("predictedKnee"));

		double base = inputs.at("/serviceMemoryBaselineMb/value").doubleValue();
		double perRps = inputs.at("/serviceMemoryPerRpsMb/value").doubleValue();
		List<Double> before = cells.stream()
			.map((row) -> row.at("/resources/" + container + "/error/memory").doubleValue())
			.toList();
		List<Double> after = cells.stream()
			.map((row) -> (base + perRps * row.get("offeredRps").doubleValue())
					/ row.at("/resources/" + container + "/measured/memoryMiB").doubleValue() - 1)
			.toList();
		assertThat(cells).hasSizeGreaterThanOrEqualTo(3);
		assertThat(before).allSatisfy((error) -> assertThat(error).isGreaterThan(0.5));
		assertThat(after).allSatisfy((error) -> assertThat(Math.abs(error)).isLessThan(0.7));
		assertThat(after.getLast()).isBetween(-0.7, -0.2);
	}

	// each pass at its rate, the container's working set what the rate gives
	private static ArrayNode staircase(String container, List<Double> rates, DoubleUnaryOperator mib) {
		ArrayNode staircase = NODES.arrayNode();
		for (double rate : rates) {
			ObjectNode pass = staircase.addObject().put("rateRps", rate);
			pass.putObject("k6").put("throughputRps", rate);
			pass.putObject("resources")
				.putObject("containers")
				.putObject(container)
				.put("workingSetMiB", mib.applyAsDouble(rate));
		}
		return staircase;
	}

	private static JsonNode calibration(String folder) {
		return ResultFiles.read(RepositoryLayout.current().results().resolve(folder).resolve("calibration.json"))
			.orElseThrow();
	}

	private static JsonNode trial(String experiment) {
		return ResultFiles
			.read(RepositoryLayout.current()
				.results()
				.resolve("trials/2026-10-31-" + experiment + "-w24")
				.resolve("calibration.json"))
			.orElseThrow();
	}

	private static ObjectNode trialPasses(JsonNode trial) {
		ObjectNode passes = NODES.objectNode();
		passes.set("idle", trial.get("idle"));
		passes.set("staircase", trial.get("staircase"));
		return passes;
	}

	// a replicate's passes as the inputs were derived from them
	private static ObjectNode replicateSet(JsonNode replicate, JsonNode calibration) {
		ObjectNode set = ((ObjectNode) replicate).deepCopy();
		set.set("low", calibration.get("passes").get("low"));
		set.set("idle", calibration.get("idle"));
		set.set("staircase", calibration.get("staircase"));
		return set;
	}

}
