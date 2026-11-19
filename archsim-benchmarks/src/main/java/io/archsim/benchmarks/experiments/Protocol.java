package io.archsim.benchmarks.experiments;

/**
 * The measuring protocol's fixed numbers (docs/validation.md, Protocol): how long each
 * load step is measured, and how often its containers are read in that window.
 */
public final class Protocol {

	/** The seconds of a calibration pass. */
	public static final int CALIBRATION_S = 30;

	/** The seconds a step warms up before k6 measures it. */
	public static final int WARMUP_S = 5;

	/** The seconds k6 measures a step. */
	public static final int DURATION_S = 20;

	/** A request's timeout, k6's and the model's clients' alike. */
	public static final int TIMEOUT_MS = 2000;

	/**
	 * The least seconds of arrivals that may wait for their answers at once before k6 has
	 * no virtual user left to send the next request: five times the objective's p95. Up
	 * to there the load is open-loop and its latencies are whole; past it k6 drops
	 * requests, and what it measured is a lower bound.
	 */
	public static final double GENERATOR_COVER_S = 0.25;

	/**
	 * The virtual users up to which a step covers its whole timeout, so that the load is
	 * open-loop until requests time out.
	 */
	public static final int GENERATOR_TIMEOUT_USERS = 1000;

	/** The virtual users k6 holds ready over those the waiting arrivals need. */
	public static final int GENERATOR_SPARE_USERS = 50;

	/**
	 * The most virtual users of a step. k6's garbage collector has each one's memory to
	 * mark, and its cycles show in the tail k6 measures even with the collector relaxed
	 * as {@code compose.yaml} relaxes it: pg-mixed's p99 at 3,000 req/s read 7 to 18 ms
	 * with 1,050 users, 35 to 38 ms with 2,050 and 68 ms with 3,050 (ADR-0086). Above
	 * 11,800 req/s a step covers less than a quarter of a second.
	 */
	public static final int GENERATOR_MOST_USERS = 3000;

	/**
	 * The seconds between readings of each container's CPU and memory in a measured
	 * window: few, so that reading them adds next to no load.
	 */
	public static final double SAMPLE_S = 5.0;

	private Protocol() {
	}

}
