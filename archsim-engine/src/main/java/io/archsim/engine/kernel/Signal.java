package io.archsim.engine.kernel;

/**
 * An event code triggers itself, without a value: a wake-up, or the end of a flush.
 */
public final class Signal extends Event {

	Signal(Simulation simulation) {
		super(simulation);
	}

	/**
	 * Triggers the event now: it is processed after the events already due now.
	 * @throws IllegalStateException when it has been triggered already
	 */
	public void succeed() {
		trigger();
	}

}
