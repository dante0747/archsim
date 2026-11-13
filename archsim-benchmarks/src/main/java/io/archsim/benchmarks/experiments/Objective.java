package io.archsim.benchmarks.experiments;

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

}
