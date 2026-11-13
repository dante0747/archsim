package io.archsim.benchmarks.inputs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How the service's CPU per request changed with load over the calibration staircase:
 * relative to the top rate, never rising, and none when it hardly changes.
 */
class LoadCurveInputTests {

	@Test
	void of_aFallingStaircase_isRelativeToTheTopRate() {
		JsonNode curve = LoadCurveInput.of(byLoad(Map.of(50.0, 0.74, 458.0, 0.31, 1129.0, 0.21, 1674.0, 0.20)))
			.orElseThrow()
			.get("value");

		assertThat(curve.get(3).get("factor").doubleValue()).isEqualTo(1.0);
		assertThat(curve.get(0).get("factor").doubleValue()).isCloseTo(0.74 / 0.20, within(1e-3));
		assertThat(curve.valueStream().map((point) -> point.get("rps").doubleValue()).toList()).containsExactly(50.0,
				458.0, 1129.0, 1674.0);
	}

	// pg-mixed's run 7: pooled with the rate below, so the top is still 1
	@Test
	void of_aTopPassDearerThanTheOneBelow_neverRises() {
		JsonNode curve = LoadCurveInput.of(byLoad(Map.of(50.0, 0.77, 408.0, 0.35, 1150.0, 0.222, 1778.0, 0.266)))
			.orElseThrow()
			.get("value");

		assertThat(List.of(curve.get(2).get("factor").doubleValue(), curve.get(3).get("factor").doubleValue()))
			.containsExactly(1.0, 1.0);
	}

	@Test
	void of_aCurveFlatWithinTenPercent_isNone() {
		assertThat(LoadCurveInput.of(byLoad(Map.of(50.0, 0.21, 500.0, 0.2)))).isEmpty();
	}

	@Test
	void of_oneRate_isNone() {
		assertThat(LoadCurveInput.of(byLoad(Map.of(500.0, 0.2)))).isEmpty();
	}

	@Test
	void of_aCalibrationWithoutAStaircase_isNone() {
		assertThat(LoadCurveInput.of(JsonNodeFactory.instance.objectNode())).isEmpty();
	}

	// 50 req/s costs at least 1.3 times the top rate: Kafka's latest staircase stopped at
	// 406 req/s (1.30); the others reach 1,000 req/s or more
	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed", "python-mixed",
			"chain", "cache-ttl" })
	void of_everyLatestCalibration_fallsToOneAtItsTopRate(String experiment) {
		JsonNode calibration = ResultFiles
			.read(RepositoryLayout.current().results().resolve(experiment).resolve("calibration.json"))
			.orElseThrow();

		Optional<ObjectNode> curve = LoadCurveInput.of(calibration);

		JsonNode points = curve.orElseThrow().get("value");
		JsonNode top = calibration.get("staircaseRps").get(calibration.get("staircaseRps").size() - 1);
		assertThat(points.get(points.size() - 1).get("rps").doubleValue()).isEqualTo(top.doubleValue());
		assertThat(points.get(points.size() - 1).get("factor").doubleValue()).isEqualTo(1.0);
		assertThat(points.get(0).get("factor").doubleValue()).isGreaterThan(1.25);
	}

	// each rate's CPU per request, in the order of the rates, as cpuByLoad records them
	private static ObjectNode byLoad(Map<Double, Double> cpuMsByRate) {
		ObjectNode passes = JsonNodeFactory.instance.objectNode();
		ArrayNode rates = passes.putArray("cpuByLoad");
		cpuMsByRate.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach((rate) -> rates.addObject()
				.put("rateRps", rate.getKey())
				.put("passes", 2)
				.put("serviceCpuMsPerRequest", rate.getValue()));
		return passes;
	}

}
