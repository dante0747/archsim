package io.archsim.app.worker;

import java.time.Duration;

/**
 * Waits before a database write is tried again: the thread sleeps in the worker, the
 * tests count the pauses.
 */
@FunctionalInterface
interface Pause {

	/** A pause that sleeps, keeping an interrupt for whoever looks next. */
	Pause SLEEP = (duration) -> {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	};

	/**
	 * Waits.
	 * @param duration how long
	 */
	void pause(Duration duration);

}
