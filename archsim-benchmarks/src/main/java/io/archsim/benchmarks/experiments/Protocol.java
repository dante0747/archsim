package io.archsim.benchmarks.experiments;

/**
 * The measuring protocol's fixed numbers (docs/validation.md, Protocol): how long each
 * load step is measured, and how often its containers are read in that window.
 */
public final class Protocol {

	/** The seconds k6 measures a step. */
	public static final int DURATION_S = 20;

	/**
	 * The seconds between readings of each container's CPU and memory in a measured
	 * window: few, so that reading them adds next to no load.
	 */
	public static final double SAMPLE_S = 5.0;

	private Protocol() {
	}

}
