package io.archsim.app.worker;

import java.util.Set;

/**
 * What a worker learns when it renews its leases.
 *
 * @param owned the runs it named that it still holds: it lost the others, which it stops
 * without writing anything
 * @param cancelRequested the held runs someone asked to stop
 */
record Renewal(Set<String> owned, Set<String> cancelRequested) {

	Renewal {
		owned = Set.copyOf(owned);
		cancelRequested = Set.copyOf(cancelRequested);
	}

}
