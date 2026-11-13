package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A query's time in PostgreSQL, its CPU shaped like the round trip and never longer, and
 * the link that is the rest: two legs that keep the round trip's mean and variance.
 */
class ServerTimesTests {

	private static final JsonNode ROUND_TRIP = JsonText.read("{\"median\": 0.238, \"p95\": 0.593}");

	private static final ObjectNode WHOLE = ModelDistributions.lognormal(0.238, 0.593);

	@Test
	void serverTime_aCpuFigure_isItsMeanShapedLikeTheRoundTrip() {
		ObjectNode server = ServerTimes.serverTime(0.17, ROUND_TRIP);

		assertThat(ModelDistributions.mean(server)).isCloseTo(0.17, within(1e-3 * 0.17));
		assertThat(server.get("p95").doubleValue() / server.get("median").doubleValue()).isCloseTo(0.593 / 0.238,
				within(1e-3 * 0.593 / 0.238));
	}

	@Test
	void linkLeg_aServerTime_keepsTheRoundTripsMeanAndVariance() {
		ObjectNode server = ServerTimes.serverTime(0.17, ROUND_TRIP);

		JsonNode leg = ServerTimes.linkLeg(ROUND_TRIP, server);

		ModelDistributions.Moments trip = ModelDistributions.moments(WHOLE);
		ModelDistributions.Moments inside = ModelDistributions.moments(server);
		ModelDistributions.Moments link = ModelDistributions.moments(leg);
		assertThat(inside.mean() + 2 * link.mean()).isCloseTo(trip.mean(), within(1e-3 * trip.mean()));
		assertThat(inside.variance() + 2 * link.variance()).isCloseTo(trip.variance(), within(1e-2 * trip.variance()));
	}

	@Test
	void serverTime_aCpuFigureLongerThanTheRoundTrip_isTheRoundTrip() {
		ObjectNode server = ServerTimes.serverTime(5.0, ROUND_TRIP);

		assertThat(ModelDistributions.mean(server)).isCloseTo(ModelDistributions.mean(WHOLE), within(1e-9));
	}

	@Test
	void serverTime_noCpuFigure_isTheRoundTripItself() {
		assertThat(ServerTimes.serverTime(null, ROUND_TRIP)).isEqualTo(WHOLE);
	}

	@Test
	void linkLeg_theWholeRoundTripInTheServer_isNone() {
		JsonNode leg = ServerTimes.linkLeg(ROUND_TRIP, ServerTimes.serverTime(null, ROUND_TRIP));

		assertThat(leg.doubleValue()).isZero();
	}

}
