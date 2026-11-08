package io.archsim.engine.simulator;

/**
 * How a run is simulated.
 */
public enum SimulationMode {

	/** The whole run. */
	FULL,

	/**
	 * A quick check: a steady load that runs much longer than it takes to settle is
	 * answered from its warm-up and a steady sample; any other run, or one whose sample
	 * is not steady, is simulated whole.
	 */
	SAMPLED

}
