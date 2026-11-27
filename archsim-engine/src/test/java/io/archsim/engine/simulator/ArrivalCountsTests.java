package io.archsim.engine.simulator;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Over a run, each traffic pattern offers about the requests its rate adds up to
 * ({@code Workload.expectedArrivals}): evenly spaced arrivals to within one request,
 * Poisson arrivals to within a few percent. Evenly spaced arrivals once took the gap to
 * the next from the rate at the last, so a ramp from 0 sent nothing for most of its run.
 */
class ArrivalCountsTests {

	/**
	 * Six thousand Poisson arrivals stray from their mean by about 1%; 5% is a wide
	 * margin.
	 */
	private static final double POISSON_PERCENT = 5;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped(),
			System::nanoTime);

	static Stream<String> patterns() {
		return Stream.of("""
				"rps": 50""", """
				"pattern": {"kind": "ramp", "startRps": 0, "endRps": 100}""", """
				"pattern": {"kind": "ramp", "startRps": 100, "endRps": 0}""", """
				"rps": 20, "pattern": {"kind": "burst", "burstRps": 200, "startS": 30, "durationS": 10}""", """
				"pattern": {"kind": "trace", "points": [[0, 0], [60, 100], [120, 0]]}""");
	}

	@ParameterizedTest
	@MethodSource("patterns")
	void run_evenlySpacedArrivals_offerTheRequestsTheRateAddsUpTo(String pattern) {
		JsonNode design = design("deterministic", pattern);

		assertThat(arrived(design)).isCloseTo(expectedArrivals(design), within(1.0));
	}

	@ParameterizedTest
	@MethodSource("patterns")
	void run_poissonArrivals_offerAboutTheRequestsTheRateAddsUpTo(String pattern) {
		JsonNode design = design("poisson", pattern);

		assertThat(arrived(design)).isCloseTo(expectedArrivals(design), withinPercentage(POISSON_PERCENT));
	}

	/**
	 * Clients calling an API for 120 s, every request measured.
	 * @param process how the arrivals are spaced
	 * @param pattern the workload's rate, as JSON members
	 * @return the design
	 */
	private static JsonNode design(String process, String pattern) {
		return SmallDesigns.clientsCallingAnApi("""
				{"seed": 7, "warmupS": 0, "durationS": 120, "arrivalProcess": "%s", %s}""".formatted(process, pattern));
	}

	private double arrived(JsonNode design) {
		return this.simulator.run(design, RunRequest.of(null)).path("summary").path("arrived").asDouble();
	}

	private static double expectedArrivals(JsonNode design) {
		return DesignDocuments.load(design).workload().expectedArrivals();
	}

}
