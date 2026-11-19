package io.archsim.benchmarks.docker;

import java.io.UncheckedIOException;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;

/**
 * When the load on a stack started: the moment its service first counted a finished
 * request since its statistics were reset. k6 starts its virtual users before its first
 * request, which takes seconds for thousands of them, so what reads through a measured
 * window counts from here, not from k6's container appearing.
 */
public final class LoadStart {

	/** How long to wait for the load to start. */
	private static final double TIMEOUT_S = 120;

	/** How often to ask the service whether the load started. */
	private static final double POLL_S = 0.2;

	private LoadStart() {
	}

	/**
	 * Waits for the load to start.
	 * @param instruments the stack's service and clock
	 * @param stopped counted down when the wait is no longer needed
	 * @return the clock's time when the service had counted a request; empty when it
	 * counted none within two minutes, or the wait was stopped first
	 */
	public static OptionalDouble await(Instruments instruments, CountDownLatch stopped) {
		Ticker ticker = instruments.ticker();
		double deadline = ticker.now() + TIMEOUT_S;
		while (stopped.getCount() > 0 && ticker.now() < deadline) {
			if (hasRequests(instruments)) {
				return OptionalDouble.of(ticker.now());
			}
			if (ticker.awaitStop(stopped, POLL_S)) {
				return OptionalDouble.empty();
			}
		}
		return OptionalDouble.empty();
	}

	private static boolean hasRequests(Instruments instruments) {
		try {
			return instruments.services().get(ServiceHttp.API + "/stats").path("requests").asDouble(0) > 0;
		}
		catch (UncheckedIOException ex) {
			return false;
		}
	}

}
