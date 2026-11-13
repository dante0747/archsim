package io.archsim.benchmarks.experiments;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The objective every experiment's knee is found against: a p95 latency within 50 ms with
 * at least 99% of the requests succeeding.
 */
public final class Objective {

	/** The p95 latency a load step may have and meet the objective (ms). */
	public static final double P95_MS = 50.0;

	/** The share of requests that must succeed. */
	public static final double MIN_SUCCESS = 0.99;

	private Objective() {
	}

	/**
	 * Whether a load step, measured or simulated, met the objective: enough of its
	 * requests succeeded, and its p95 is within the latency.
	 * @param step the step: its {@code successRate} and {@code p95}, either None
	 * @return whether it met the objective
	 */
	public static boolean isMetBy(JsonNode step) {
		JsonNode p95 = PythonValues.get(step, "p95");
		return PythonValues.numberOrZero(step, "successRate") >= MIN_SUCCESS && !PythonValues.isNone(p95)
				&& p95.doubleValue() <= P95_MS;
	}

}
