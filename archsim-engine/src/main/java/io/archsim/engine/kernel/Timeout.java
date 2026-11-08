package io.archsim.engine.kernel;

/**
 * An event that happens after a delay. It is scheduled when it is made, whether or not
 * anything waits for it.
 */
public final class Timeout extends Event {

	Timeout(Simulation simulation, double delay) {
		super(simulation);
		if (delay < 0) {
			throw new IllegalArgumentException("Negative delay " + delay);
		}
		scheduleTriggered(Simulation.NORMAL, delay);
	}

}
