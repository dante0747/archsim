package io.archsim.app.worker;

/**
 * What a worker says each time it beats: who it is, how many runs it takes at once, how
 * many it has, and whether it is giving them back to stop.
 *
 * @param worker the worker
 * @param slots the runs it takes at once
 * @param running the runs it has
 * @param draining whether it is stopping, its runs being given back
 */
record Heartbeat(WorkerIdentity worker, int slots, int running, boolean draining) {

	/**
	 * The worker's status in {@code job_workers}.
	 * @return {@code draining} while it stops, else {@code running}
	 */
	String status() {
		return this.draining ? "draining" : "running";
	}

}
