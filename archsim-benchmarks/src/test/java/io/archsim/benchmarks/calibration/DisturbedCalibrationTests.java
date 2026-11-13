package io.archsim.benchmarks.calibration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.inputs.ModelInputs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A calibration whose first pass at the top rate stalls even on its retry, as pg-mixed's
 * did in the trial: it gives no replicate, and stops the staircase rising further.
 */
class DisturbedCalibrationTests {

	private static FakePasses passes;

	private static ObjectNode calibration;

	@BeforeAll
	static void calibrate() {
		passes = new FakePasses("up 3"::equals);
		calibration = new Calibration(passes, new Staircase(1), new ModelInputs(Simulations.onTheEngine())::of,
				new Logs().log())
			.calibrate(Experiments.standard().get("pg-mixed"));
	}

	@Test
	void calibrate_aDisturbedTopPass_isReplacedByAnother() {
		assertThat(passes.labels()).filteredOn((label) -> label.contains(" top ") || label.contains(" down 3 "))
			.containsExactly("read down 3 @2485", "read top 3 @2485", "read top 4 @2485");
	}

	@Test
	void calibrate_aDisturbedTopPass_leavesThreeReplicates() {
		assertThat(calibration.get("inputReplicates").size()).isEqualTo(Calibration.REPLICATES);
	}

	@Test
	void calibrate_aDisturbedTopPass_isKeptForTheRecord() {
		assertThat(calibration.get("disturbedReplicates").valueStream().map((pass) -> pass.at("/k6/p95").doubleValue()))
			.containsExactly(46.0);
	}

	@Test
	void calibrate_aDisturbedTopPass_namesTheReplicatesUsed() {
		assertThat(calibration.get("method").stringValue()).contains("median of 3 replicates");
	}

	// retried once, then kept with its reason
	@Test
	void calibrate_aDisturbedPass_isDiscardedWithItsReason() {
		assertThat(calibration.get("discardedPasses").valueStream().map((pass) -> pass.get("reason").stringValue()))
			.containsExactly("p95 above 10x the median");
	}

}
