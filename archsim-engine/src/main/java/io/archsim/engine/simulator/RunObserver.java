package io.archsim.engine.simulator;

/**
 * Whoever waits for a run, as the Python engine's {@code progress} and
 * {@code should_cancel} hooks: told how far the run has got after each chunk it
 * simulates, and 1.0 once its report is written; asked whether to stop wherever the run
 * looks at its wall clock, before it. A quick check answered from a sample reports the
 * sample's progress; one whose sample is not steady reports the sample's, then the whole
 * run's from the start. Both methods are called on the thread that simulates the run, so
 * a cancel asked for on another thread must be published safely, through a
 * {@code volatile} field or an atomic, for the run to see it.
 */
public interface RunObserver {

	/** No one: progress goes nowhere, and the run is never cancelled. */
	RunObserver NONE = new RunObserver() {
	};

	/**
	 * Called after each chunk with how far the run has got, and with 1.0 once its report
	 * is written.
	 * @param fraction the run's simulated time over its end, or 1.0
	 */
	default void progress(double fraction) {
	}

	/**
	 * Whether the run should stop now; asked every few thousand events and after each
	 * chunk, so it answers quickly.
	 * @return {@code true} to end the run with a {@link SimulationCancelledException}
	 */
	default boolean cancelRequested() {
		return false;
	}

}
