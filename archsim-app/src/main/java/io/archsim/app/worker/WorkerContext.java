package io.archsim.app.worker;

import java.time.Clock;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * Who a worker is and what it works with.
 *
 * @param identity the worker
 * @param slots the runs it simulates at once ({@code ARCHSIM_MAX_CONCURRENT_RUNS})
 * @param limits its limits on a run ({@code ARCHSIM_MAX_REQUESTS},
 * {@code ARCHSIM_MAX_WALL_SECONDS}), which a run's own tighten
 * @param timings its lease, heartbeat, reaps and cancel grace
 * @param clock the time it writes
 */
record WorkerContext(WorkerIdentity identity, int slots, SimulationLimits limits, WorkerProperties timings,
		Clock clock) {

	/**
	 * The worker's name, in its leases and its {@code job_workers} row.
	 * @return its name
	 */
	String workerId() {
		return this.identity.id();
	}

}
