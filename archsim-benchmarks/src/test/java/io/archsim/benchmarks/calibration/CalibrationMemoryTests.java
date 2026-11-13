package io.archsim.benchmarks.calibration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.inputs.ModelInputs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The calibration hands its whole staircase to the memory fit: a service holding 40 MiB
 * at rest and 0.05 MiB more for each request a second.
 */
class CalibrationMemoryTests {

	private static ObjectNode calibration;

	@BeforeAll
	static void calibrate() {
		calibration = new Calibration(FakePasses.withMemory(), new Staircase(1),
				new ModelInputs(Simulations.onTheEngine())::of, new Logs().log())
			.calibrate(Experiments.standard().get("pg-mixed"));
	}

	@Test
	void calibrate_theStaircasesWorkingSets_fitTheServicesMemoryPerRequest() {
		assertThat(calibration.at("/inputs/serviceMemoryPerRpsMb/value").doubleValue()).isCloseTo(0.05, within(1e-6));
	}

	// once, at the top level
	@Test
	void calibrate_theReplicates_leaveTheStaircaseOut() {
		assertThat(calibration.get("replicates").valueStream()).noneMatch((replicate) -> replicate.has("staircase"));
	}

}
