package io.archsim.app.meta;

/**
 * What this server runs: ArchSim's version, the engine, schema and report versions, its
 * database and the limits of a run.
 *
 * @param version ArchSim's own version, the release's (ADR-0076)
 * @param engineVersion the engine that simulates the runs
 * @param schemaVersion the design document's schema
 * @param reportVersion the version of the reports runs produce
 * @param database the database's dialect, always {@code postgresql}
 * @param limits the most a run may take
 */
record Meta(String version, String engineVersion, String schemaVersion, String reportVersion, String database,
		Limits limits) {

	/**
	 * The most a run may take.
	 *
	 * @param maxRequests the requests it may simulate
	 * @param maxWallSeconds the wall-clock seconds it may run
	 * @param maxConcurrentRuns the runs a worker simulates at once
	 */
	record Limits(long maxRequests, double maxWallSeconds, int maxConcurrentRuns) {

	}

}
