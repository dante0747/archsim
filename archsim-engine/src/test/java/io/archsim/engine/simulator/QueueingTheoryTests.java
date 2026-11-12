package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * The simulator against queueing theory, an oracle that does not move with the engine:
 * with Poisson arrivals and exponential service one service is an M/M/c queue, whose mean
 * response time Erlang C gives exactly, and the starter's API and database keep Little's
 * law. The frozen Python engine's reference benchmark held it to the same tolerances.
 */
class QueueingTheoryTests {

	private static final double MS_PER_SECOND = 1000.0;

	/** Requests a service serves a second, at its mean time. */
	private static final double MU = MS_PER_SECOND / ReferenceDesigns.MEAN_SERVICE_MS;

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	/**
	 * The mean time in system of an M/M/c queue whose service takes
	 * {@link ReferenceDesigns#MEAN_SERVICE_MS} on average, by Erlang C.
	 * @param lambda the arrivals a second
	 * @param servers the servers, c
	 * @return the mean response time in milliseconds
	 */
	private static double erlangCMeanResponseMs(double lambda, int servers) {
		double offered = lambda / MU;
		double term = 1.0;
		double belowC = 0.0;
		for (int k = 0; k < servers; k++) {
			belowC += term;
			term *= offered / (k + 1);
		}
		double atC = term / (1 - offered / servers);
		double waits = atC / (belowC + atC);
		return (waits / (servers * MU - lambda) + 1 / MU) * MS_PER_SECOND;
	}

	private JsonNode simulate(double rps, int servers, double durationS, long seed) {
		return this.simulator.run(ReferenceDesigns.singleService(rps, servers, durationS, seed), RunRequest.of(null));
	}

	@ParameterizedTest(name = "M/M/{1} at {0} requests a second", quoteTextArguments = false)
	@CsvSource({ "50, 1, 400", "80, 1, 400", "320, 4, 200" })
	void run_mmcQueue_meanResponseIsErlangCs(double rps, int servers, double durationS) {
		JsonNode report = simulate(rps, servers, durationS, 2024);

		assertThat(report.at("/summary/latencyMs/mean").doubleValue()).isCloseTo(erlangCMeanResponseMs(rps, servers),
				withinPercentage(8));
	}

	@ParameterizedTest(name = "M/M/{1} at {0} requests a second", quoteTextArguments = false)
	@CsvSource({ "50, 1, 400", "80, 1, 400", "320, 4, 200" })
	void run_mmcQueue_utilizationIsTheOfferedLoad(double rps, int servers, double durationS) {
		JsonNode report = simulate(rps, servers, durationS, 2024);

		assertThat(ReferenceDesigns.summaryOf(report, "svc").required("utilization").doubleValue())
			.isCloseTo(rps / (servers * MU), withinPercentage(5));
	}

	@Test
	void run_mm1AtHalfLoad_medianResponseIsTheExponentials() {
		// an M/M/1 queue's response time is exponential with rate mu - lambda
		double lambda = 50;

		JsonNode report = simulate(lambda, 1, 400, 99);

		assertThat(report.at("/summary/latencyMs/p50").doubleValue())
			.isCloseTo(Math.log(2) / (MU - lambda) * MS_PER_SECOND, withinPercentage(8));
	}

	@ParameterizedTest
	@ValueSource(strings = { "api", "db" })
	void run_starter_keepsLittlesLawAtTheQueueingPart(String id) {
		JsonNode report = this.simulator.run(ReferenceDesigns.example("cache-aside-starter.json"), RunRequest.of(null));

		JsonNode part = ReferenceDesigns.summaryOf(report, id);
		double timeInSystemS = (part.required("meanQueueWaitMs").doubleValue()
				+ part.required("meanHoldMs").doubleValue()) / MS_PER_SECOND;
		assertThat(part.required("meanInSystem").doubleValue())
			.isCloseTo(part.required("arrivalRps").doubleValue() * timeInSystemS, withinPercentage(10));
	}

}
