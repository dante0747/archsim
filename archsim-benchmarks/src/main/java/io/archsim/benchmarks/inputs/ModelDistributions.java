package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.distribution.Distribution;

/**
 * The distributions the model inputs are written as: a lognormal by its median and p95,
 * or by its mean and the shape of a measured time, and the mean the engine samples.
 */
public final class ModelDistributions {

	/** The standard normal's 95th percentile, as the harness has always written it. */
	static final double Z95 = 1.6448536269514722;

	/** The narrowest p95/median ratio a lognormal input gets. */
	static final double MIN_SPREAD = 1.01;

	private static final double SMALLEST_MEDIAN = 0.001;

	private static final double NO_MEDIAN = 1e-9;

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private ModelDistributions() {
	}

	/**
	 * A lognormal input: its median at least 0.001 ms and its p95 at least 1% above it,
	 * both to four decimals.
	 * @param median the median (ms)
	 * @param p95 the 95th percentile (ms)
	 * @return the distribution
	 */
	public static ObjectNode lognormal(double median, double p95) {
		double floored = Math.max(median, SMALLEST_MEDIAN);
		return NODES.objectNode()
			.put("kind", "lognormal")
			.put("median", PythonRound.round(floored, DECIMALS))
			.put("p95", PythonRound.round(Math.max(p95, floored * MIN_SPREAD), DECIMALS));
	}

	/**
	 * A lognormal with the given mean and the p95/median ratio of a measured time.
	 * @param mean the mean (ms)
	 * @param shapeMedian the measured time's median
	 * @param shapeP95 the measured time's p95
	 * @return the distribution
	 */
	public static ObjectNode lognormalWithMean(double mean, double shapeMedian, double shapeP95) {
		double ratio = Math.max(shapeP95 / Math.max(shapeMedian, NO_MEDIAN), MIN_SPREAD);
		double sigma = StrictMath.log(ratio) / Z95;
		double median = mean / StrictMath.exp(sigma * sigma / 2);
		return lognormal(median, median * ratio);
	}

	/**
	 * A lognormal with the given mean and the p95/median ratio of a measured time.
	 * @param mean the mean (ms)
	 * @param shape the measured time: its {@code median} and {@code p95}
	 * @return the distribution
	 */
	public static ObjectNode lognormalWithMean(double mean, JsonNode shape) {
		return lognormalWithMean(mean, shape.get("median").doubleValue(), shape.get("p95").doubleValue());
	}

	/**
	 * The mean and variance of a lognormal input (ms, ms squared).
	 * @param lognormal the distribution
	 * @return its mean and its variance
	 */
	static Moments moments(JsonNode lognormal) {
		double sigma = StrictMath.log(lognormal.get("p95").doubleValue() / lognormal.get("median").doubleValue()) / Z95;
		double mean = lognormal.get("median").doubleValue() * StrictMath.exp(sigma * sigma / 2);
		return new Moments(mean, mean * mean * (StrictMath.exp(sigma * sigma) - 1));
	}

	/**
	 * The mean of a model input's distribution as the engine samples it.
	 * @param distribution the distribution, as the design holds it
	 * @return its mean (ms)
	 */
	public static double mean(JsonNode distribution) {
		return RecordCodec.read(distribution, Distribution.class).expected();
	}

	/**
	 * A lognormal's mean and variance.
	 *
	 * @param mean the mean (ms)
	 * @param variance the variance (ms squared)
	 */
	record Moments(double mean, double variance) {

	}

}
