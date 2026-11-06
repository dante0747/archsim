package io.archsim.engine.export;

/**
 * The host ports a compose file publishes its proxies on: one after another, from 8080,
 * each taken by the proxy that publishes it.
 */
final class HostPorts {

	/** The host port of the first proxy. */
	private static final int FIRST = 8080;

	/** The port the next proxy takes; the only state, moved on by {@link #take()}. */
	private int next = FIRST;

	/**
	 * The next free host port.
	 * @return the port, which is no longer free
	 */
	int take() {
		return this.next++;
	}

}
