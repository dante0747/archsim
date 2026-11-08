package io.archsim.engine.kernel;

/**
 * A process: a flow of steps that waits for events between them, as a SimPy process
 * yields them. It starts with an urgent event, so its first step runs ahead of every
 * normal event due at the same time, and it happens itself, at normal priority, when its
 * body calls the finish it was given. It has no value: a result its waiters read (as the
 * Python engine reads a process's value after waiting for it) is set by the step that
 * finishes it.
 */
public final class Routine extends Event {

	Routine(Simulation simulation, Body body) {
		super(simulation);
		Signal start = new Signal(simulation);
		start.addCallback((event) -> body.start(this::trigger));
		start.scheduleTriggered(Simulation.URGENT, 0);
	}

	/**
	 * What a process does.
	 */
	@FunctionalInterface
	public interface Body {

		/**
		 * Runs the process's first step.
		 * @param finish to call once, when the process is done
		 */
		void start(Runnable finish);

	}

}
