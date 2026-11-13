package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The model inputs' distributions, against what {@code run.py} wrote for the same
 * numbers.
 */
class ModelDistributionsTests {

	@Test
	void lognormal_aMedianAndAP95_isWrittenToFourDecimals() {
		JsonNode lognormal = ModelDistributions.lognormal(1.23456789, 1.2);

		assertThat(lognormal).isEqualTo(JsonText.read("""
				{"kind": "lognormal", "median": 1.2346, "p95": 1.2469}"""));
	}

	// the floors: a median of at least 0.001 ms, a p95 1% above it before rounding
	@Test
	void lognormal_aTinyMedian_isFloored() {
		JsonNode lognormal = ModelDistributions.lognormal(0.0001, 0.0001);

		assertThat(lognormal).isEqualTo(JsonText.read("""
				{"kind": "lognormal", "median": 0.001, "p95": 0.001}"""));
	}

	@Test
	void lognormalWithMean_aMeasuredShape_keepsTheMean() {
		JsonNode lognormal = ModelDistributions.lognormalWithMean(2.0, JsonText.read("""
				{"median": 1.0, "p95": 3.0}"""));

		assertThat(lognormal).isEqualTo(JsonText.read("""
				{"kind": "lognormal", "median": 1.6001, "p95": 4.8004}"""));
	}

	// a shape with no median takes the narrowest spread
	@Test
	void lognormalWithMean_aShapeWithoutAMedian_isTheNarrowest() {
		JsonNode lognormal = ModelDistributions.lognormalWithMean(0.8, JsonText.read("""
				{"median": 0.0, "p95": 0.0}"""));

		assertThat(lognormal).isEqualTo(JsonText.read("""
				{"kind": "lognormal", "median": 0.8, "p95": 0.808}"""));
	}

	@Test
	void moments_aLognormal_haveItsMean() {
		ModelDistributions.Moments moments = ModelDistributions.moments(JsonText.read("""
				{"kind": "lognormal", "median": 0.5, "p95": 2.0}"""));

		assertThat(moments.mean()).isEqualTo(0.7132058053567356);
	}

	// CPython on Windows gives 0.5262877194497134: its C library's
	// exp(0.71032385...) rounds to the nearer double, where StrictMath's
	// misses it by a hair, one unit in the last place
	@Test
	void moments_aLognormal_haveItsVarianceToTheLastBitButOne() {
		ModelDistributions.Moments moments = ModelDistributions.moments(JsonText.read("""
				{"kind": "lognormal", "median": 0.5, "p95": 2.0}"""));

		assertThat(moments.variance()).isCloseTo(0.5262877194497134, within(2e-16));
	}

	@Test
	void mean_aLognormal_isTheMeanTheEngineSamples() {
		double mean = ModelDistributions.mean(JsonText.read("""
				{"kind": "lognormal", "median": 0.5, "p95": 2.0}"""));

		assertThat(mean).isEqualTo(0.7132058053567356);
	}

	@Test
	void mean_anExponential_isItsMean() {
		double mean = ModelDistributions.mean(JsonText.read("""
				{"kind": "exponential", "mean": 0.25}"""));

		assertThat(mean).isEqualTo(0.25);
	}

}
