package io.archsim.benchmarks.designs;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The highest load meeting the objective, by doubling then bisection to 1%, or by 25%
 * steps from a knee found before. The simulations are stubbed: a design meets the
 * objective up to a knee and misses it above.
 */
class KneeSearchTests {

	private static final double KNEE = 1234.0;

	@Test
	void knee_fromNothing_isTheKneeToOnePercent() {
		ObjectNode knee = KneeSearch.knee(upTo(KNEE), PythonValues.none());

		assertThat(knee.get("kneeRps").doubleValue()).isCloseTo(KNEE, within(0.01 * KNEE));
	}

	@ParameterizedTest
	@ValueSource(doubles = { 300, 1000, 1234, 1500, 5000 })
	void knee_nearAKneeFoundBefore_isTheKneeToOnePercentAndNoHigher(double near) {
		ObjectNode knee = KneeSearch.knee(upTo(KNEE), PythonValues.number(near));

		assertThat(knee.get("kneeRps").doubleValue()).isCloseTo(KNEE, within(0.01 * KNEE)).isLessThanOrEqualTo(KNEE);
	}

	@ParameterizedTest
	@ValueSource(doubles = { 300, 1000, 1234, 1500, 5000 })
	void knee_nearAKneeFoundBefore_triesHardlyMoreLoadsThanASearchFromNothing(double near) {
		int fromNothing = KneeSearch.knee(upTo(KNEE), PythonValues.none()).get("tried").size();

		ObjectNode knee = KneeSearch.knee(upTo(KNEE), PythonValues.number(near));

		assertThat(knee.get("tried").size()).isLessThan(fromNothing + 3);
	}

	@Test
	void knee_aDesignThatNeverMeetsTheObjective_isZero() {
		ObjectNode knee = KneeSearch.knee(upTo(0.0), PythonValues.number(500.0));

		assertThat(knee.get("kneeRps").doubleValue()).isZero();
	}

	@Test
	void knee_eachLoadTried_isItsLoadP95AndSuccessRate() {
		ObjectNode knee = KneeSearch.knee(upTo(KNEE), PythonValues.none());

		assertThat(knee.get("tried").get(0)).isEqualTo(
				JsonNodeFactory.instance.objectNode().put("offeredRps", 100.0).put("p95", 5.0).put("successRate", 1.0));
	}

	// a p95 of 5 ms up to the knee, 500 ms above it
	private static Function<JsonNode, ObjectNode> upTo(double knee) {
		return (rps) -> JsonNodeFactory.instance.objectNode()
			.set("offeredRps", rps)
			.put("p95", (rps.doubleValue() <= knee) ? 5.0 : 500.0)
			.put("successRate", 1.0);
	}

}
