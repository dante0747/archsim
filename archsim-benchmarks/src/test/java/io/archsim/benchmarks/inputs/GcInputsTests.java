package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * W21: the Node.js service's garbage collection from its {@code /stats}: the mark-compact
 * cycles the model pauses for, and the scavenges left in the CPU per request; a Java or
 * Go service's stop-the-world pauses.
 */
class GcInputsTests {

	/** 300 scavenges (240 ms), 5 mark-compact cycles: 60 ms final, 40 ms incremental. */
	private static final String MARK_COMPACTS = """
			{"requests": 50000, "cpuMs": 10000.0, "cpuMsPerRequest": 0.2,
			 "gc": {"collections": 310, "pauseMs": 340.0,
			        "byKind": {"minor": {"collections": 300, "pauseMs": 240.0, "median": 0.8},
			                   "major": {"collections": 5, "pauseMs": 60.0, "median": 12.0},
			                   "incremental": {"collections": 5, "pauseMs": 40.0, "median": 8.0}}}}""";

	// 100 ms over 5 cycles: a mean of 20 ms, the model's median 20 / 1.17
	@Test
	void node_markCompactCycles_areThePausesTheModelSamples() {
		GcInputs.Node gc = GcInputs.node(JsonText.read(MARK_COMPACTS));

		double sigma = StrictMath.log(2.5) / ModelDistributions.Z95;
		assertThat(gc.inputs().get("gcPauseMs").get("value").doubleValue())
			.isCloseTo(20 / StrictMath.exp(sigma * sigma / 2), within(1e-3));
	}

	@Test
	void node_markCompactCycles_areSpacedByTheRequestsBetweenThem() {
		GcInputs.Node gc = GcInputs.node(JsonText.read(MARK_COMPACTS));

		assertThat(gc.inputs().get("gcEveryRequests").get("value").longValue()).isEqualTo(10_000);
	}

	@Test
	void node_markCompactCycles_addThePausePerRequestToTakeOffTheCpu() {
		GcInputs.Node gc = GcInputs.node(JsonText.read(MARK_COMPACTS));

		assertThat(gc.perRequestMs()).isCloseTo(100.0 / 50_000, within(1e-12));
	}

	// a running sum gives 0.6000000000000001; Python's sum, compensated since 3.12, 0.6
	@Test
	void node_pausesARunningSumRounds_addUpAsPythonsSum() {
		GcInputs.Node gc = GcInputs.node(JsonText.read("""
				{"requests": 1, "gc": {"byKind": {"major": {"collections": 1, "pauseMs": 0.1},
				                                  "incremental": {"collections": 1, "pauseMs": 0.2},
				                                  "weakcb": {"collections": 1, "pauseMs": 0.3}}}}"""));

		assertThat(gc.perRequestMs()).isEqualTo(0.6);
	}

	@Test
	void node_scavenges_stayInTheCpuPerRequest() {
		GcInputs.Node gc = GcInputs.node(JsonText.read(MARK_COMPACTS));

		assertThat(gc.inputs().get("gcPauseMs").get("how").stringValue())
			.contains("300 scavenges, 240.0 ms, left in the CPU per request");
	}

	@Test
	void node_scavengesAlone_pauseNothing() {
		GcInputs.Node gc = GcInputs.node(JsonText.read("""
				{"requests": 50000, "cpuMs": 10000.0,
				 "gc": {"collections": 40, "pauseMs": 30.0,
				        "byKind": {"minor": {"collections": 40, "pauseMs": 30.0, "median": 0.75}}}}"""));

		assertThat(gc.inputs().propertyNames()).containsExactly("gcPauseMs");
		assertThat(gc.inputs().get("gcPauseMs").get("value").intValue()).isZero();
		assertThat(gc.perRequestMs()).isZero();
	}

	// a service before W21 reported no GC: nothing
	// is added, so the designs stay as they were
	@Test
	void node_aServiceThatReportedNoGc_addsNothing() {
		GcInputs.Node gc = GcInputs.node(JsonText.read("{\"requests\": 10, \"cpuMs\": 1.0}"));

		assertThat(gc).isEqualTo(GcInputs.Node.none());
	}

}
