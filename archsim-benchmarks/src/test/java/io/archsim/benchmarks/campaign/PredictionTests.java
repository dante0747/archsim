package io.archsim.benchmarks.campaign;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pg-mixed's latest calibration predicted on the engine, without a range fit: the knee,
 * the ladder around it, the engine's versions.
 */
class PredictionTests {

	private static ObjectNode predictions;

	@BeforeAll
	static void predict(@TempDir Path folder) {
		JsonNode calibration = ResultFiles
			.read(RepositoryLayout.current().results().resolve("pg-mixed/calibration.json"))
			.orElseThrow();
		predictions = new Prediction(Simulations.onTheEngine(), folder.resolve("no-fit.json"),
				() -> JsonText.read("{\"commit\": \"abc\"}"), new Logs().log())
			.searchingAgainAt(List.of())
			.of(Experiments.standard().get("pg-mixed"), calibration);
	}

	@Test
	void of_aCalibration_namesTheEnginesVersions() {
		assertThat(predictions.get("engineVersion").stringValue()).isEqualTo(EngineVersions.ENGINE);
		assertThat(predictions.get("reportVersion").stringValue()).isEqualTo(EngineVersions.REPORT);
	}

	// SeedKneesTests has the searches at other seeds; here none is asked for
	@Test
	void of_aCalibration_keepsTheKneeBySeedTheDesignsOwnFirst() {
		assertThat(predictions.at("/kneeBySeed/knees/0/seed").intValue()).isEqualTo(42);
		assertThat(predictions.at("/kneeBySeed/knees/0/kneeRps")).isEqualTo(predictions.at("/knee/kneeRps"));
	}

	@Test
	void of_aCalibration_simulatesTheLadderAroundItsKnee() {
		double knee = predictions.at("/knee/kneeRps").doubleValue();

		assertThat(predictions.get("steps").valueStream().map((step) -> step.get("offeredRps").longValue()))
			.containsExactlyElementsOf(Prediction.steps(knee));
	}

	@Test
	void of_noRangeFit_predictsNoRange() {
		assertThat(predictions.has("kneeRange")).isFalse();
	}

	@Test
	void of_aCalibration_keepsTheProvenanceItWasGiven() {
		assertThat(predictions.get("provenance")).isEqualTo(JsonText.read("{\"commit\": \"abc\"}"));
	}

	@Test
	void of_aCalibration_designsTheKnee() {
		assertThat(predictions.at("/design/workload/rps")).isEqualTo(predictions.at("/knee/kneeRps"));
	}

	@Test
	void steps_aKnee_areItsSharesRounded() {
		assertThat(Prediction.steps(1000)).containsExactly(250L, 500L, 750L, 900L, 1000L, 1100L, 1250L, 1500L);
	}

	// round half to even, 10 req/s at least, each once
	@Test
	void steps_aLowKnee_areTenAtLeastEachOnce() {
		assertThat(Prediction.steps(30)).containsExactly(10L, 15L, 22L, 27L, 30L, 33L, 38L, 45L);
	}

}
