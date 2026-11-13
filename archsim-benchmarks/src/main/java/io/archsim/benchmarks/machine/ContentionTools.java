package io.archsim.benchmarks.machine;

import java.util.concurrent.Executor;

/**
 * What a contention check's parts run with.
 *
 * @param tools the machine checks' tools
 * @param probes the probe containers
 * @param counters Windows' counters, read throughout
 * @param watch what watches k6's CPU beside its runs
 */
record ContentionTools(CheckTools tools, Probes probes, HostCounters counters, Executor watch) {

	private static final double MILLISECONDS = 1000.0;

	/**
	 * Now, on the host's clock.
	 * @return milliseconds since the epoch
	 */
	double hostMs() {
		return this.tools.instruments().ticker().now() * MILLISECONDS;
	}

}
