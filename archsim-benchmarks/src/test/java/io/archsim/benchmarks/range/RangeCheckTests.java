package io.archsim.benchmarks.range;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The range's check, fitted in-sample, leaving out each run and leaving out each
 * experiment: never with a run's own error in its fit outside in-sample. One experiment's
 * runs are far off, the others' close.
 */
class RangeCheckTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<String> EXPERIMENTS = List.of("a", "b", "c");

	private static final List<JsonNode> CALIBRATIONS = EXPERIMENTS.stream()
		.flatMap((experiment) -> Stream.of(0, 1, 2).map((index) -> calibration(experiment, index)))
		.toList();

	private static final List<CountedRun> RUNS = EXPERIMENTS.stream()
		.flatMap((experiment) -> Stream.of(0, 1, 2)
			.map((index) -> run(experiment, index, experiment.equals("c") ? 0.6 : 0.02 * (index - 1))))
		.toList();

	@Test
	void check_everyFold_checksEveryRunAtTheNominalCoverage() {
		RangeCheck.Result check = RangeCheck.check(CALIBRATIONS, RUNS);

		assertThat(check.counts().propertyNames()).containsExactlyElementsOf(RangeCheck.FOLDS.keySet());
		assertThat(check.counts().valueStream().map((fold) -> fold.get("0.8").get(1).intValue()))
			.containsOnly(RUNS.size());
	}

	// in-sample, the far experiment's error widens every
	// range; left out, the others' errors do not cover it
	@Test
	void check_aFarOffRunLeftOutWithItsExperiment_hasANarrowerRangeThatMissesIt() {
		JsonNode far = RangeCheck.check(CALIBRATIONS, RUNS).detail().getLast();

		assertThat(far.at("/leaveExperimentOut/modelSigma").doubleValue())
			.isLessThan(far.at("/inSample/modelSigma").doubleValue());
		assertThat(far.at("/leaveExperimentOut/holds").booleanValue()).isFalse();
	}

	@Test
	void check_aFarOffRunLeftOut_isNoWiderThanInSample() {
		JsonNode far = RangeCheck.check(CALIBRATIONS, RUNS).detail().getLast();

		assertThat(far.at("/leaveRunOut/modelSigma").doubleValue())
			.isLessThanOrEqualTo(far.at("/inSample/modelSigma").doubleValue());
	}

	@Test
	void check_aCloseRunLeftOutWithItsExperiment_isInItsRange() {
		JsonNode near = RangeCheck.check(CALIBRATIONS, RUNS).detail().getFirst();

		assertThat(near.at("/leaveExperimentOut/holds").booleanValue()).isTrue();
	}

	private static JsonNode calibration(String experiment, int index) {
		ObjectNode row = NODES.objectNode().put("experiment", experiment).put("source", experiment + index);
		row.putObject("scales").put("serviceTimeMs", 0.2 * (1 + 0.05 * index));
		return row;
	}

	private static CountedRun run(String experiment, int index, double error) {
		ObjectNode measured = NODES.objectNode().put("kneeRps", 1000.0 * Math.exp(error));
		ObjectNode sensitivity = NODES.objectNode().put("serviceTimeMs", -1.0);
		return new CountedRun(experiment, experiment + index, PythonValues.none(), measured,
				PythonValues.number(1000.0), sensitivity, error, PythonValues.none());
	}

}
