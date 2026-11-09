package io.archsim.engine.simulator;

import java.io.Serial;

/**
 * A run stopped because whoever was waiting for it asked it to stop, as the Python
 * engine's {@code SimulationCancelledError}.
 */
public final class SimulationCancelledException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A cancelled run, in the Python engine's words.
	 */
	public SimulationCancelledException() {
		super("simulation cancelled");
	}

}
