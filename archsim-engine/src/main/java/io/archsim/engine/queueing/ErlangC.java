package io.archsim.engine.queueing;

import java.util.OptionalDouble;

/**
 * The Erlang C formula: the chance that an arrival waits in an M/M/c queue, computed
 * through the Erlang B recursion, which stays stable for thousands of servers.
 */
public final class ErlangC {

	private ErlangC() {
	}

	/**
	 * The chance that an arrival waits for one of {@code servers} servers.
	 * @param servers how many arrivals are served at once
	 * @param offeredLoad the arrival rate over one server's service rate, in erlangs
	 * @return the chance to wait; zero without load, and none when the queue is unstable
	 * (at least as much load as servers)
	 */
	public static OptionalDouble waitProbability(int servers, double offeredLoad) {
		if (offeredLoad <= 0) {
			return OptionalDouble.of(0.0);
		}
		if (servers <= 0 || offeredLoad >= servers) {
			return OptionalDouble.empty();
		}
		double blocking = 1.0;
		for (int k = 1; k <= servers; k++) {
			blocking = offeredLoad * blocking / (k + offeredLoad * blocking);
		}
		double utilisation = offeredLoad / servers;
		return OptionalDouble.of(blocking / (1.0 - utilisation * (1.0 - blocking)));
	}

}
