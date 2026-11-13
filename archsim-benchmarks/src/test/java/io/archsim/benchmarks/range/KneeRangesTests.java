package io.archsim.benchmarks.range;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The range adds the calibration's run-to-run variation for the design and the model's
 * own error, as a lognormal around the predicted knee cut at its nominal coverage.
 */
class KneeRangesTests {

	private static final JsonNode FIT = JsonText.read("""
			{"at": "2026-11-01T00:00:00Z", "engineVersion": "test",
			 "inputs": {"serviceTimeMs": {"sigma": 0.1}, "commitFlushMs": {"sigma": 0.2}},
			 "fallbackSigma": 0.05, "correlation": 0.0, "model": {"sigma": 0.2}, "runs": 12, "experiments": 4}""");

	private static final JsonNode SENSITIVITY = JsonText
		.read("{\"serviceTimeMs\": -1.0, \"commitFlushMs\": -0.5, \"dbLinkLatencyMs\": 0.2}");

	private static final JsonNode KNEE = PythonValues.integer(1000);

	// each input's variation times the knee's
	// sensitivity, an unseen input at the fallback
	@Test
	void calibrationSigma_independentInputs_addInQuadrature() {
		assertThat(KneeRanges.calibrationSigma(SENSITIVITY, FIT))
			.isCloseTo(Math.sqrt(0.1 * 0.1 + (0.5 * 0.2) * (0.5 * 0.2) + (0.2 * 0.05) * (0.2 * 0.05)), within(1e-12));
	}

	// with a common correlation, inputs that move together add up
	@Test
	void calibrationSigma_fullyCorrelatedInputs_addUp() {
		ObjectNode correlated = ((ObjectNode) FIT).deepCopy().put("correlation", 1.0);

		assertThat(KneeRanges.calibrationSigma(SENSITIVITY, correlated)).isCloseTo(Math.abs(-0.1 - 0.1 + 0.01),
				within(1e-12));
	}

	@Test
	void of_aKnee_isTheLognormalAroundItAtTheNominalCoverage() {
		ObjectNode range = KneeRanges.of(KNEE, SENSITIVITY, FIT, KneeRanges.COVERAGE).orElseThrow();

		double sigma = Math.hypot(KneeRanges.calibrationSigma(SENSITIVITY, FIT), 0.2);
		double quantile = PythonStatistics.normalInverse(0.9);
		assertThat(range.get("nominalCoverage").doubleValue()).isEqualTo(KneeRanges.COVERAGE).isEqualTo(0.8);
		assertThat(range.get("sigma").doubleValue()).isCloseTo(sigma, within(1e-4));
		assertThat(range.get("lowRps").longValue()).isEqualTo(Math.round(1000 * Math.exp(-quantile * sigma)));
		assertThat(range.get("highRps").longValue()).isEqualTo(Math.round(1000 * Math.exp(quantile * sigma)));
	}

	@Test
	void of_aKnee_namesTheFitItCameFrom() {
		ObjectNode range = KneeRanges.of(KNEE, SENSITIVITY, FIT, KneeRanges.COVERAGE).orElseThrow();

		assertThat(range.at("/fit/engineVersion").stringValue()).isEqualTo("test");
		assertThat(range.at("/fit/commit").isNull()).isTrue();
	}

	@Test
	void of_aWiderCoverage_isAWiderRange() {
		ObjectNode range = KneeRanges.of(KNEE, SENSITIVITY, FIT, KneeRanges.COVERAGE).orElseThrow();

		ObjectNode wider = KneeRanges.of(KNEE, SENSITIVITY, FIT, 0.9).orElseThrow();

		assertThat(wider.get("lowRps").longValue()).isLessThan(range.get("lowRps").longValue());
		assertThat(wider.get("highRps").longValue()).isGreaterThan(range.get("highRps").longValue());
	}

	@Test
	void of_noFitOrNoKnee_isNone() {
		assertThat(KneeRanges.of(KNEE, SENSITIVITY, PythonValues.none(), KneeRanges.COVERAGE)).isEmpty();
		assertThat(KneeRanges.of(PythonValues.integer(0), SENSITIVITY, FIT, KneeRanges.COVERAGE)).isEmpty();
	}

	@Test
	void of_aKnee_holdsAMeasuredKneeAtItAndNotOneTwiceAsHigh() {
		ObjectNode range = KneeRanges.of(KNEE, JsonText.read("{\"serviceTimeMs\": -1.0}"), FIT, KneeRanges.COVERAGE)
			.orElseThrow();

		assertThat(Knees.inRange(range, JsonText.read("{\"kneeRps\": 1000.0}"))).contains(true);
		assertThat(Knees.inRange(range, JsonText.read("{\"kneeRps\": 2000.0}"))).contains(false);
	}

	@Test
	void of_aKnee_holdsAKneeAboveTheLadderWhenItReachesPastTheTop() {
		ObjectNode range = KneeRanges.of(KNEE, JsonText.read("{\"serviceTimeMs\": -1.0}"), FIT, KneeRanges.COVERAGE)
			.orElseThrow();
		ObjectNode above = JsonText.object("{\"kneeRps\": null, \"bound\": \"above\"}");
		above.put("aboveRps", range.get("highRps").longValue() - 1);

		assertThat(Knees.inRange(range, above)).contains(true);
	}

}
