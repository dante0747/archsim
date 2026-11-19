package io.archsim.benchmarks.designs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's instant estimate of a benchmark design, on pg-mixed's fifteenth run: its
 * service's one core fills at about 5,097 req/s.
 */
class EstimatesTests {

	private static final Estimates ESTIMATES = Estimates.onTheEngine();

	private static final JsonNode INPUTS = ResultFiles
		.read(RepositoryLayout.current().results().resolve("pg-mixed/run-15/calibration.json"))
		.orElseThrow()
		.get("inputs");

	@Test
	void estimate_aDesignBelowSaturation_givesItsP95AndTheLoadItSaturatesAt() {
		ObjectNode estimate = ESTIMATES.estimate(design(5000));

		assertThat(estimate.get("offeredRps")).isEqualTo(PythonValues.integer(5000));
		assertThat(estimate.get("p95").doubleValue()).isEqualTo(29.38);
		assertThat(estimate.get("successRate").doubleValue()).isEqualTo(1.0);
		assertThat(estimate.get("overloaded").booleanValue()).isFalse();
		assertThat(estimate.get("saturationRps").doubleValue()).isEqualTo(5096.8);
	}

	@Test
	void estimate_aDesignBelowSaturation_givesTheEstimatesOwnSummary() {
		ObjectNode estimate = ESTIMATES.estimate(design(5000));

		assertThat(estimate.get("summary").stringValue()).isEqualTo("p95 ≈ 29 ms; busiest Node.js service 98%");
	}

	// the estimate fails no request: without a latency it has no success rate either
	@Test
	void estimate_aDesignPastSaturation_givesNoP95AndNoSuccessRate() {
		ObjectNode estimate = ESTIMATES.estimate(design(6000));

		assertThat(PythonValues.isNone(estimate.get("p95"))).isTrue();
		assertThat(PythonValues.isNone(estimate.get("successRate"))).isTrue();
		assertThat(estimate.get("overloaded").booleanValue()).isTrue();
	}

	@Test
	void knee_ofADesignsEstimates_isTheHighestLoadWhoseP95MeetsTheObjective() {
		ObjectNode knee = Estimates.knee((rps) -> ESTIMATES.estimate(design(rps)));

		assertThat(knee.get("kneeRps")).isEqualTo(PythonValues.integer(5000));
	}

	private static ObjectNode design(int rps) {
		return design(PythonValues.integer(rps));
	}

	private static ObjectNode design(JsonNode rps) {
		return Designs.design(Experiments.standard().get("pg-mixed"), INPUTS, rps, Designs.Window.STEP);
	}

}
