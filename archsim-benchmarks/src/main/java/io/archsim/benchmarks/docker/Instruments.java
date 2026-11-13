package io.archsim.benchmarks.docker;

/**
 * What the harness drives and reads a running stack with: compose, the containers' cgroup
 * counters, the services' endpoints and the clock.
 *
 * @param compose the stacks, with the running stack's variables
 * @param cgroups the containers' counters
 * @param services the services' endpoints
 * @param ticker the clock
 */
public record Instruments(Compose compose, Cgroups cgroups, ServiceHttp services, Ticker ticker) {

	/**
	 * The same instruments on a stack's compose.
	 * @param stack the stack's compose, its variables set
	 * @return the instruments
	 */
	public Instruments on(Compose stack) {
		return new Instruments(stack, this.cgroups.on(stack), this.services, this.ticker);
	}

}
