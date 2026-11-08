package io.archsim.engine.kernel;

/**
 * An event code triggers itself with a value: the outcome of a call delivered to a caller
 * that waits with a timeout, or a scheduled pod.
 *
 * @param <T> the value's type
 */
public final class Promise<T> extends ValueEvent<T> {

	Promise(Simulation simulation) {
		super(simulation);
	}

	/**
	 * Triggers the event now with a value.
	 * @param value the value
	 * @throws IllegalStateException when it has been triggered already
	 */
	public void succeed(T value) {
		succeedWith(value);
	}

}
