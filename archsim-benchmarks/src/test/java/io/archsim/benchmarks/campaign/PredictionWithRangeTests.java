package io.archsim.benchmarks.campaign;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pg-mixed's latest calibration predicted with the committed range fit: the knee's
 * sensitivity searched on the engine, and its range around the knee.
 */
class PredictionWithRangeTests {

	private static ObjectNode predictions;

	@BeforeAll
	static void predict() {
		RepositoryLayout layout = RepositoryLayout.current();
		JsonNode calibration = ResultFiles.read(layout.results().resolve("pg-mixed/calibration.json")).orElseThrow();
		predictions = new Prediction(Simulations.onTheEngine(), layout.kneeRange(), () -> JsonText.read("{}"),
				new Logs().log())
			.of(Experiments.standard().get("pg-mixed"), calibration);
	}

	@Test
	void of_aRangeFit_predictsTheKneesRange() {
		assertThat(predictions.at("/kneeRange/lowRps").doubleValue())
			.isLessThan(predictions.at("/knee/kneeRps").doubleValue());
		assertThat(predictions.at("/kneeRange/highRps").doubleValue())
			.isGreaterThan(predictions.at("/knee/kneeRps").doubleValue());
	}

	@Test
	void of_aRangeFit_holdsFourFifthsOfTheSpread() {
		assertThat(predictions.at("/kneeRange/nominalCoverage").doubleValue()).isEqualTo(0.8);
	}

}
