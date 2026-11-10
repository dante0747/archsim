package io.archsim.engine.calibration.prometheus.http;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.core5.concurrent.Cancellable;

/**
 * The one deadline of a query. When it passes, a watchdog cancels the request it watches,
 * closing its connection, so neither slow headers nor a trickled body can hold the query
 * past it, as the Python engine's watchdog shut its socket down.
 */
final class Deadline {

	/** When the deadline passes, in {@link System#nanoTime()}'s terms. */
	private final long at;

	private final AtomicBoolean expired = new AtomicBoolean();

	private Deadline(long at) {
		this.at = at;
	}

	/**
	 * A deadline from now.
	 * @param seconds how long from now
	 * @return the deadline
	 */
	static Deadline after(double seconds) {
		return new Deadline(System.nanoTime() + Math.round(seconds * TimeUnit.SECONDS.toNanos(1)));
	}

	/**
	 * Cancels a request when the deadline passes, until the watch is closed.
	 * @param request the request
	 * @return the watch, to close when the query ends
	 */
	Watch watch(Cancellable request) {
		return new Watch(this, Thread.ofVirtual().start(() -> expire(request)));
	}

	/**
	 * Whether the deadline has passed, or the watchdog cancelled the request.
	 * @return whether the query is too late
	 */
	boolean passed() {
		return this.expired.get() || System.nanoTime() >= this.at;
	}

	/**
	 * Whether the watchdog cancelled the request, which may end a body early.
	 * @return whether it did
	 */
	boolean cancelled() {
		return this.expired.get();
	}

	private void expire(Cancellable request) {
		try {
			TimeUnit.NANOSECONDS.sleep(this.at - System.nanoTime());
		}
		catch (InterruptedException ex) {
			// the query ended in time
			return;
		}
		this.expired.set(true);
		request.cancel();
	}

	/**
	 * A watchdog over one request: it cancels the request when the deadline passes, until
	 * it is closed.
	 *
	 * @param deadline the deadline it keeps
	 * @param watchdog the thread that waits for it
	 */
	record Watch(Deadline deadline, Thread watchdog) implements AutoCloseable {

		/**
		 * Stops watching: the query ended.
		 */
		@Override
		public void close() {
			this.watchdog.interrupt();
		}

	}

}
