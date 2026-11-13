package io.archsim.benchmarks.designs;

import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The design of a kept run's inputs at its first measured step, simulated here, against
 * the step the Python harness simulated with the Java engine before it was 1.0.0
 * ({@code results/dev/0.16.0-java/}): the same figures, but for the result digest, which
 * names the engine.
 */
class RecordedStepsTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Simulations SIMULATIONS = Simulations.onTheEngine();

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "pg-mixed, 2026-11-04T11-52-16Z", "cache-aside, 2026-11-04T12-50-54Z", "kafka, 2026-11-04T13-27-44Z",
			"chain, 2026-11-04T05-34-15Z", "java-mixed, 2026-11-04T03-38-18Z", "cache-ttl, 2026-11-04T06-11-13Z" })
	void simulate_theFirstStepOfAKeptRun_isTheRecordedStep(String experiment, String calibratedAt) {
		Path dev = LAYOUT.results().resolve("dev/0.16.0-java").resolve(experiment).resolve(calibratedAt + ".json");
		JsonNode check = ResultFiles.read(dev).orElseThrow();
		ObjectNode recorded = ((ObjectNode) check.get("steps").get(0)).deepCopy();

		ObjectNode step = SIMULATIONS.simulate(Designs.design(Experiments.standard().get(experiment),
				check.get("inputs"), recorded.get("offeredRps"), Designs.Window.STEP));

		step.remove("resultDigest");
		recorded.remove("resultDigest");
		assertThat(PythonJson.writeIndented(step, 2)).isEqualTo(PythonJson.writeIndented(recorded, 2));
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({ "pg-mixed, 2026-11-04T11-52-16Z", "kafka, 2026-11-04T13-27-44Z", "cache-ttl, 2026-11-04T06-11-13Z" })
	void knee_ofAKeptRunsInputs_isTheRecordedKnee(String experiment, String calibratedAt) {
		Path dev = LAYOUT.results().resolve("dev/0.16.0-java").resolve(experiment).resolve(calibratedAt + ".json");
		JsonNode check = ResultFiles.read(dev).orElseThrow();

		ObjectNode knee = SIMULATIONS.knee(Experiments.standard().get(experiment), check.get("inputs"),
				PythonValues.none(), Designs.Window.STEP);

		assertThat(PythonJson.writeIndented(knee, 2)).isEqualTo(PythonJson.writeIndented(check.get("knee"), 2));
	}

}
