package io.archsim.benchmarks.docker;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The wall clock a campaign reads and waits on, which a test replaces with its own.
 */
public interface Ticker {

	/**
	 * Now.
	 * @return seconds since the epoch
	 */
	double now();

	/**
	 * Waits for a time.
	 * @param seconds how long
	 */
	void sleep(double seconds);

	/**
	 * Waits for a time or until a stop, whichever comes first.
	 * @param stop the stop
	 * @param seconds the longest wait
	 * @return whether it stopped
	 */
	boolean awaitStop(CountDownLatch stop, double seconds);

	/**
	 * This machine's clock.
	 * @return the ticker
	 */
	static Ticker system() {
		return new SystemTicker(Clock.systemUTC());
	}

	/**
	 * This machine's clock and waits.
	 *
	 * @param clock the clock
	 */
	record SystemTicker(Clock clock) implements Ticker {

		private static final double NANOSECONDS = 1e9;

		private static final double MILLISECONDS = 1e3;

		@Override
		public double now() {
			Instant now = this.clock.instant();
			return now.getEpochSecond() + now.getNano() / NANOSECONDS;
		}

		@Override
		public void sleep(double seconds) {
			try {
				Thread.sleep((long) (seconds * MILLISECONDS));
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while waiting", ex);
			}
		}

		@Override
		public boolean awaitStop(CountDownLatch stop, double seconds) {
			try {
				return stop.await((long) (seconds * NANOSECONDS), TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return true;
			}
		}

	}

}
