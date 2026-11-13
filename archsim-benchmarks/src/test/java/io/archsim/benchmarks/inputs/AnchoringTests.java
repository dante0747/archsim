package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.experiments.Protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The service time's spread anchored at the top calibration pass: narrowed, keeping its
 * mean, until the model's p95 - p50 there is k6's, and never widened. The simulations are
 * stubbed: each returns a p95 - p50 that grows with the service time's p95/median ratio.
 */
class AnchoringTests {

	private static final Experiment CACHE_ASIDE = Experiments.standard().get("cache-aside");

	/** Cache-aside's run 7: its own time spreads 11.4 times from median to p95. */
	private static final String WIDE = """
			{"kind": "lognormal", "median": 0.0618, "p95": 0.7035}""";

	/** A service time the spread leaves alone, so the anchor alone simulates. */
	private static final String EXPONENTIAL = """
			{"kind": "exponential", "mean": 0.2}""";

	/** k6's p95 - p50: 1 ms. */
	private static final JsonNode TOP = JsonText.read("""
			{"rateRps": 3350.0, "k6": {"p50": 0.416, "p95": 1.416}}""");

	private static final double SIMULATED_P50 = 0.3;

	@Test
	void anchorSpread_aSpreadWiderThanK6s_narrowsItToK6sKeepingItsMean() {
		ObjectNode inputs = inputs(WIDE);

		new Anchoring(stub((ratio) -> 0.2 * ratio, new ArrayList<>())).anchorSpread(CACHE_ASIDE, inputs, TOP);

		JsonNode value = inputs.get("serviceTimeMs").get("value");
		assertThat(0.2 * ratio(value)).isCloseTo(1.0, within(Anchoring.SPREAD_TOLERANCE + 0.01));
		assertThat(ModelDistributions.mean(value)).isCloseTo(ModelDistributions.mean(JsonText.read(WIDE)),
				within(1e-3 * ModelDistributions.mean(JsonText.read(WIDE))));
	}

	@Test
	void anchorSpread_aSpreadWiderThanK6s_saysWhatItAnchored() {
		ObjectNode inputs = inputs(WIDE);

		new Anchoring(stub((ratio) -> 0.2 * ratio, new ArrayList<>())).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.get("serviceTimeMs").get("how").stringValue())
			.startsWith("mean = the CPU per request; spread anchored: ")
			.contains("instead of 11.38, so that the model's p95 - p50 at 3350 req/s is k6's");
	}

	// at the pass's rate, without the client latency, briefly, and few times
	@Test
	void anchorSpread_aSpreadWiderThanK6s_simulatesThePassBrieflyWithoutTheClientLatency() {
		List<Call> calls = new ArrayList<>();

		new Anchoring(stub((ratio) -> 0.2 * ratio, calls)).anchorSpread(CACHE_ASIDE, inputs(WIDE), TOP);

		assertThat(calls).hasSizeLessThanOrEqualTo(1 + Anchoring.SPREAD_STEPS)
			.allSatisfy((call) -> assertThat(call)
				.isEqualTo(new Call(3350.0, new Designs.Window(Protocol.WARMUP_S, Anchoring.SPREAD_S), 0.0)));
	}

	@Test
	void anchorSpread_aSpreadWiderThanK6s_leavesTheClientLatencyToTheAnchor() {
		ObjectNode inputs = inputs(WIDE);

		new Anchoring(stub((ratio) -> 0.2 * ratio, new ArrayList<>())).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.get("clientLinkLatencyMs").get("value").doubleValue()).isEqualTo(0.08);
	}

	// pg-mixed, pg-hot-row and Kafka in v6: the model's tail at the top pass is k6's
	// or less, and what it misses may not be the service's, so it is never widened
	@Test
	void anchorSpread_aSpreadNoWiderThanK6s_isKeptAfterOneSimulation() {
		ObjectNode inputs = inputs(WIDE);
		List<Call> calls = new ArrayList<>();

		new Anchoring(stub((ratio) -> 0.05 * ratio, calls)).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs).isEqualTo(inputs(WIDE));
		assertThat(calls).hasSize(1);
	}

	@Test
	void anchorSpread_aTailWiderThanK6sHoweverNarrow_stopsAtTheNarrowestSpread() {
		ObjectNode inputs = inputs(WIDE);

		new Anchoring(stub((ratio) -> 2.0 + 0.1 * ratio, new ArrayList<>())).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(ratio(inputs.get("serviceTimeMs").get("value"))).isBetween(ModelDistributions.MIN_SPREAD, 1.1);
	}

	@Test
	void anchorSpread_aTailTheSpreadDoesNotMove_leavesIt() {
		ObjectNode inputs = inputs(WIDE);

		new Anchoring(stub((ratio) -> 3.0, new ArrayList<>())).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs).isEqualTo(inputs(WIDE));
	}

	@Test
	void anchorSpread_anExponentialServiceTime_isLeftWithoutSimulating() {
		ObjectNode inputs = inputs("""
				{"kind": "exponential", "mean": 0.2}""");
		List<Call> calls = new ArrayList<>();

		new Anchoring(stub((ratio) -> ratio, calls)).anchorSpread(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.get("serviceTimeMs").get("value"))
			.isEqualTo(JsonText.read("{\"kind\": \"exponential\", \"mean\": 0.2}"));
		assertThat(calls).isEmpty();
	}

	@Test
	void anchorSpread_aPassWithoutK6sP95_isLeftWithoutSimulating() {
		ObjectNode inputs = inputs(WIDE);
		List<Call> calls = new ArrayList<>();

		new Anchoring(stub((ratio) -> ratio, calls)).anchorSpread(CACHE_ASIDE, inputs,
				JsonText.read("{\"rateRps\": 3350.0, \"k6\": {\"p50\": 0.4}}"));

		assertThat(inputs).isEqualTo(inputs(WIDE));
		assertThat(calls).isEmpty();
	}

	// the model's median 0.3 ms at the pass, k6's 0.416 ms: (0.416 - 0.3) / 2 each way
	@Test
	void anchor_aModelFasterThanK6_takesHalfTheGapEachWay() {
		ObjectNode inputs = inputs(EXPONENTIAL);

		new Anchoring(median(0.3, new ArrayList<>())).anchor(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.at("/clientLinkLatencyMs/value").doubleValue()).isEqualTo(0.058);
		assertThat(inputs.at("/clientLinkLatencyMs/how").stringValue()).isEqualTo(
				"anchored: (measured median 0.416 ms - the model's median without it 0.300 ms at 3350 req/s) / 2, one "
						+ "way, at least 0");
	}

	@Test
	void anchor_aModelSlowerThanK6_floorsTheLatencyAtZero() {
		ObjectNode inputs = inputs(EXPONENTIAL);

		new Anchoring(median(1.0, new ArrayList<>())).anchor(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.at("/clientLinkLatencyMs/value").doubleValue()).isZero();
	}

	@Test
	void anchor_aPass_simulatesItsWholeLengthWithoutTheClientLatency() {
		List<Call> calls = new ArrayList<>();

		new Anchoring(median(0.3, calls)).anchor(CACHE_ASIDE, inputs(EXPONENTIAL), TOP);

		assertThat(calls).containsExactly(new Call(3350.0, new Designs.Window(Protocol.WARMUP_S, 30), 0.0));
	}

	// the model's median with the latency added both ways, less k6's
	@Test
	void anchor_aPass_recordsTheAnchorAndItsResidual() {
		ObjectNode inputs = inputs(EXPONENTIAL);

		new Anchoring(median(1.0, new ArrayList<>())).anchor(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.get("anchor")).isEqualTo(JsonText.read("""
				{"rateRps": 3350.0, "measuredP50": 0.416, "modelP50WithoutClientLatency": 1.0,
				 "residualMs": 0.584}"""));
	}

	// no median from the simulation: None recorded, and the gap taken from 0
	@Test
	void anchor_aSimulationWithoutAMedian_recordsNoneAndAnchorsFromZero() {
		ObjectNode inputs = inputs(EXPONENTIAL);

		new Anchoring((experiment, probe, rps, window) -> JsonNodeFactory.instance.objectNode().putNull("p50"))
			.anchor(CACHE_ASIDE, inputs, TOP);

		assertThat(inputs.at("/anchor/modelP50WithoutClientLatency").isNull()).isTrue();
		assertThat(inputs.at("/clientLinkLatencyMs/value").doubleValue()).isEqualTo(0.208);
	}

	private static ObjectNode inputs(String serviceTime) {
		ObjectNode inputs = JsonNodeFactory.instance.objectNode();
		inputs.putObject("serviceTimeMs").set("value", JsonText.read(serviceTime));
		((ObjectNode) inputs.get("serviceTimeMs")).put("how", "mean = the CPU per request");
		inputs.putObject("clientLinkLatencyMs").put("value", 0.08).put("how", "x");
		return inputs;
	}

	private static double ratio(JsonNode value) {
		return value.get("p95").doubleValue() / value.get("median").doubleValue();
	}

	// p50 0.3 ms and p95 0.3 ms plus the spread
	// at the service time's ratio; each call kept
	private static DesignSimulation stub(DoubleUnaryOperator spread, List<Call> calls) {
		return (experiment, probe, rps, window) -> {
			calls.add(new Call(rps.doubleValue(), window, probe.get("clientLinkLatencyMs").get("value").doubleValue()));
			double modelSpread = spread.applyAsDouble(ratio(probe.get("serviceTimeMs").get("value")));
			return JsonNodeFactory.instance.objectNode()
				.put("p50", SIMULATED_P50)
				.put("p95", SIMULATED_P50 + modelSpread);
		};
	}

	// a simulation whose median is this, whatever it is asked
	private static DesignSimulation median(double p50, List<Call> calls) {
		return (experiment, probe, rps, window) -> {
			calls.add(new Call(rps.doubleValue(), window, probe.get("clientLinkLatencyMs").get("value").doubleValue()));
			return JsonNodeFactory.instance.objectNode().put("p50", p50).put("p95", p50);
		};
	}

	/**
	 * What one simulation was asked.
	 *
	 * @param rate the offered load
	 * @param window the simulated seconds
	 * @param clientLatency the client latency the probe had
	 */
	private record Call(double rate, Designs.Window window, double clientLatency) {

	}

}
