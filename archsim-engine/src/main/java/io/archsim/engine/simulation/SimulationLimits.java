package io.archsim.engine.simulation;

/**
 * The bounds that keep a run from using unbounded time or memory.
 *
 * @param maxRequests the most requests a run may generate
 * @param maxWallSeconds the longest a run may take, in seconds of wall-clock time
 */
public record SimulationLimits(long maxRequests, double maxWallSeconds) {

	/** The engine's own limits: two million requests and five minutes. */
	public static final SimulationLimits DEFAULT = new SimulationLimits(2_000_000, 300.0);

	/**
	 * Whether a run expected to generate so many requests is within the request limit. A
	 * count that is not a number is not: the comparison is written so that NaN fails it.
	 * @param expectedRequests the requests the run's workload expects to generate
	 * @return whether the run may be simulated
	 */
	public boolean allowsRequests(double expectedRequests) {
		return expectedRequests <= this.maxRequests;
	}

}
