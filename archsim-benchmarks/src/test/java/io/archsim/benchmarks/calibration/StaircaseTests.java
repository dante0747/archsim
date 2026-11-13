package io.archsim.benchmarks.calibration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staircase's next rate: 65% of the service's CPUs at the pass's CPU per request, at
 * most half of any other capacity it implies; none once a pass is slow or the rise small.
 */
class StaircaseTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	@Test
	void nextRise_aSlowPass_stops() {
		ObjectNode slow = pass(1.0);
		((ObjectNode) slow.get("k6")).put("p95", Staircase.P95_CAP_MS + 1);

		assertThat(new Staircase(1).nextRise(PG_MIXED, slow)).isEmpty();
	}

	// 65% of a core is 43 req/s: no rise
	@Test
	void nextRise_aCostlyPass_stops() {
		assertThat(new Staircase(1).nextRise(PG_MIXED, pass(15.0))).isEmpty();
	}

	@Test
	void hops_aChainsGateway_isItAndTheApiBehindIt() {
		assertThat(
				Staircase.hops(chain().get("service")).stream().map((hop) -> hop.get("cpuMsPerRequest").doubleValue()))
			.containsExactly(1.0, 2.0);
	}

	// 65% of one CPU at the API's 2 ms
	@Test
	void nextRise_aChain_capsTheBusierHop() {
		assertThat(new Staircase(1).nextRise(PG_MIXED, chain())).hasValue(325);
	}

	// one CPU over 2 ms a request
	@Test
	void capacityBound_aChain_isTheBusierHops() {
		assertThat(new Staircase(1).capacityBound(PG_MIXED, chain())).isEqualTo(500);
	}

	@Test
	void nextRise_aServiceWithOneCpu_risesTo65PercentOfIt() {
		assertThat(new Staircase(1).nextRise(PG_MIXED, pass(2.0))).hasValue(325);
	}

	// a service with two CPUs: twice as far
	@Test
	void nextRise_aServiceWithTwoCpus_risesTwiceAsFar() {
		assertThat(new Staircase(2).nextRise(PG_MIXED, pass(2.0))).hasValue(650);
	}

	// one write at a time on the hot row: 1,000 / 2 ms, half of it
	@Test
	void nextRise_theHotRow_isHalfItsLocksCapacity() {
		assertThat(new Staircase(4).nextRise(Experiments.standard().get("pg-hot-row"), pass(0.1))).hasValue(250);
	}

	// three consumers at 4 ms a message: 750 req/s, half of it
	@Test
	void nextRise_kafka_isHalfItsConsumersCapacity() {
		ObjectNode entry = pass(0.1);
		entry.set("consumer", JsonText.read("{\"timings\": {\"consume\": {\"mean\": 4.0}}}"));

		assertThat(new Staircase(4).nextRise(Experiments.standard().get("kafka"), entry)).hasValue(375);
	}

	// a pass at 50 req/s whose service took so much CPU per request
	private static ObjectNode pass(double cpuMsPerRequest) {
		return JsonText.object("""
				{"rateRps": 50, "k6": {"p50": 1.0, "p95": 2.0},
				 "service": {"cpuMsPerRequest": %s,
				   "timings": {"db_read": {"mean": 0.5}, "db_write": {"mean": 2.0}}}}""".formatted(cpuMsPerRequest));
	}

	// chain's gateway at 1 ms a request, the API behind it at 2
	private static ObjectNode chain() {
		ObjectNode entry = pass(1.0);
		ObjectNode service = (ObjectNode) entry.get("service");
		ObjectNode upstream = service.putObject("upstream").put("cpuMsPerRequest", 2.0);
		upstream.set("timings", service.get("timings"));
		return entry;
	}

}
