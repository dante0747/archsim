package io.archsim.benchmarks.calibration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.inputs.ModelInputs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Protocol v6 on pg-mixed with the laptop's falling CPU per request: 50 req/s at 1.0 ms,
 * so the next rate is 65% of a core (650); at 0.26 ms 65% of the core would be 2,486,
 * just under half the pool's bound for the writes (2 ms each: 10 connections, 2,500
 * req/s), so 2,485; then no rise of at least 10% is left.
 */
class CalibrationTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final ModelInputs INPUTS = new ModelInputs(Simulations.onTheEngine());

	private static FakePasses passes;

	private static ObjectNode calibration;

	@BeforeAll
	static void calibrate() {
		passes = new FakePasses((label) -> false);
		calibration = new Calibration(passes, new Staircase(1), INPUTS::of, new Logs().log()).calibrate(PG_MIXED);
	}

	@Test
	void calibrate_theLaptopsService_climbsTwoRises() {
		assertThat(calibration.get("staircaseRps")).isEqualTo(JsonText.read("[50, 650.0, 2485.0]"));
	}

	@Test
	void calibrate_aCalibration_namesItsProtocol() {
		assertThat(calibration.get("protocol").stringValue()).isEqualTo("v7");
	}

	@Test
	void calibrate_aCalibration_namesItsProtocolInItsMethod() {
		assertThat(calibration.get("method").stringValue()).endsWith("settled first (v7)");
	}

	@Test
	void calibrate_aCalibration_recordsItsStaircasesRule() {
		assertThat(calibration.get("staircaseRule")).isEqualTo(JsonText
			.read("{\"cpuCap\": 0.65, \"boundShare\": 0.5, \"p95CapMs\": 10.0, \"maxRises\": 5, \"minRise\": 1.1}"));
	}

	@Test
	void calibrate_theLaptopsService_runsUpDownAndTheTopOnceMore() {
		assertThat(passes.labels()).filteredOn((label) -> label.startsWith("read"))
			.containsExactly("read up 1 @50", "read up 2 @650", "read up 3 @2485", "read down 3 @2485",
					"read down 2 @650", "read down 1 @50", "read top 3 @2485");
	}

	// the auxiliary mix right after each top pass, at the same rate
	@Test
	void calibrate_eachTopPass_runsTheAuxiliaryMixAfterIt() {
		assertThat(passes.labels()).filteredOn((label) -> label.startsWith("write_async"))
			.containsExactly("write_async write_async 1 @2485", "write_async write_async 2 @2485",
					"write_async write_async 3 @2485");
	}

	@Test
	void calibrate_threeTopPasses_giveThreeReplicates() {
		assertThat(calibration.get("replicates").size()).isEqualTo(Calibration.REPLICATES);
	}

	@Test
	void calibrate_threeTopPasses_giveThreeSetsOfInputs() {
		assertThat(calibration.get("inputReplicates").size()).isEqualTo(Calibration.REPLICATES);
	}

	@Test
	void calibrate_eachRate_countsItsPasses() {
		assertThat(calibration.get("cpuByLoad").valueStream().map((rate) -> rate.get("passes").intValue()))
			.containsExactly(2, 2, 3);
	}

	// run.py's first set took the staircase's last pass, labelled, as its top pass
	@Test
	void calibrate_theFirstReplicate_isTheLabelledLastPassUp() {
		assertThat(calibration.at("/passes/high/label").stringValue()).isEqualTo("up 3");
	}

	// the later sets took the pass itself, which has no label
	@Test
	void calibrate_aLaterReplicate_isThePassItself() {
		assertThat(calibration.at("/replicates/1/high").has("label")).isFalse();
	}

	// net of the idle 1 ms a second
	@Test
	void calibrate_eachRate_isItsCpuPerRequestNetOfIdle() {
		JsonNode byLoad = calibration.get("cpuByLoad");

		assertThat(byLoad.get(0).get("serviceCpuMsPerRequest").doubleValue()).isCloseTo(FakePasses.laptopCpu(50),
				within(1e-4));
		assertThat(byLoad.get(2).get("serviceCpuMsPerRequest").doubleValue()).isCloseTo(FakePasses.laptopCpu(2485),
				within(1e-4));
	}

	@Test
	void calibrate_theTopPass_setsTheServicesCpuPerRequestNetOfIdle() {
		String how = calibration.at("/inputs/serviceTimeMs/how").stringValue();

		assertThat(how).startsWith("the median of 3 replicates (")
			.contains("mean = Node's CPU per request at 2485 req/s net of idle")
			.contains("= %.4f ms".formatted(FakePasses.laptopCpu(2485)));
	}

}
