package io.archsim.engine.run;

import java.io.Serial;
import java.util.Objects;

/**
 * A run over its limits: it would generate more requests than it may simulate.
 */
public final class SimulationLimitException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A refusal, in the words the run's owner reads.
	 * @param message why, and what to change
	 */
	public SimulationLimitException(String message) {
		super(Objects.requireNonNull(message, "message"));
	}

	/**
	 * Why the run is refused.
	 * @return the refusal's words, never {@code null}
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

}
