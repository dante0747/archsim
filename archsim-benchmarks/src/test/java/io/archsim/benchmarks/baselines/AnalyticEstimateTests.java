package io.archsim.benchmarks.baselines;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knee by the engine's analytic estimate, on stubbed estimates: one whose p95 is 10
 * ms below 1,000 req/s and 100 ms from there, and one that gives no p95 at all.
 */
class AnalyticEstimateTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	// doubling to 800 and 1,600 req/s, then bisection to 1%: 994 met, 1,000 missed
	@Test
	void of_anEstimateWithAP95_isTheHighestLoadWhoseP95MeetsTheObjective() {
		ObjectNode estimate = AnalyticEstimate.of(withinTheObjectiveBelow(1000));

		assertThat(estimate.get("kneeRps")).isEqualTo(PythonValues.integer(994));
		assertThat(estimate.get("basis").stringValue()).isEqualTo("p95");
	}

	// the estimate has no success rate: the one the search reads is not the estimate's
	@Test
	void of_anEstimateWithAP95_recordsEachLoadItTriedAndItsP95Alone() {
		ObjectNode estimate = AnalyticEstimate.of(withinTheObjectiveBelow(1000));

		assertThat(estimate.get("tried").get(0)).isEqualTo(JsonText.read("{\"offeredRps\": 100.0, \"p95\": 10.0}"));
	}

	// the search halves from 100 req/s down to 1; its zero is not a load the estimate met
	@Test
	void of_anEstimateOverTheObjectiveAtEveryLoadTried_hasNoKneeAndSaysSo() {
		ObjectNode estimate = AnalyticEstimate.of(withinTheObjectiveBelow(0));

		assertThat(PythonValues.isNone(estimate.get("kneeRps"))).isTrue();
		assertThat(estimate.get("basis").stringValue()).isEqualTo("p95");
		assertThat(estimate.has("atKnee")).isFalse();
		assertThat(estimate.get("why").stringValue()).isEqualTo(
				"the estimate's p95 is over the objective at every load tried, down to 1 req/s: it gives no knee");
	}

	// the search goes below its first load, 100 req/s, when that one misses
	@Test
	void of_anEstimateOverTheObjectiveAtTheFirstLoadTried_stillFindsTheKneeBelowIt() {
		ObjectNode estimate = AnalyticEstimate.of(withinTheObjectiveBelow(50));

		assertThat(estimate.get("kneeRps")).isEqualTo(PythonValues.integer(49));
	}

	@Test
	void of_anEstimateWithAP95_recordsWhatItSaysAtTheKnee() {
		ObjectNode estimate = AnalyticEstimate.of(withinTheObjectiveBelow(1000));

		assertThat(estimate.get("atKnee")).isEqualTo(JsonText.read("""
				{"p95": 10.0, "overloaded": false, "saturationRps": 2000.0, "summary": "p95 about 10 ms"}
				"""));
	}

	@Test
	void of_anEstimateWithoutAP95_takesTheLoadItSaturatesAtAndSaysSo() {
		Function<JsonNode, ObjectNode> noLatency = (rps) -> estimate(rps, null, 1234.5);

		ObjectNode estimate = AnalyticEstimate.of(noLatency);

		assertThat(estimate.get("kneeRps").doubleValue()).isEqualTo(1234.5);
		assertThat(estimate.get("basis").stringValue()).isEqualTo("saturation");
		assertThat(estimate.get("how").stringValue()).contains("gave no p95");
	}

	private static Function<JsonNode, ObjectNode> withinTheObjectiveBelow(double limit) {
		return (rps) -> estimate(rps, (rps.doubleValue() < limit) ? 10.0 : 100.0, 2000.0);
	}

	// an estimate as the harness reads it: no success rate without a latency
	private static ObjectNode estimate(JsonNode rps, @Nullable Double p95, double saturation) {
		ObjectNode estimate = NODES.objectNode().set("offeredRps", rps);
		estimate.put("p95", p95);
		estimate.put("successRate", (p95 != null) ? 1.0 : null);
		estimate.put("overloaded", false);
		estimate.put("saturationRps", saturation);
		return estimate.put("summary", (p95 != null) ? "p95 about " + Math.round(p95) + " ms" : "no latency");
	}

}
