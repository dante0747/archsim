package io.archsim.app.worker;

import java.util.Set;

/**
 * What a worker asks the queue for.
 *
 * @param workerId the worker, who will hold the runs' leases
 * @param limit the most runs it takes: its free slots
 * @param exclude runs it is still executing after it lost their leases, which it must not
 * take twice
 */
record ClaimRequest(String workerId, int limit, Set<String> exclude) {

	ClaimRequest {
		exclude = Set.copyOf(exclude);
	}

}
