package io.archsim.benchmarks;

import java.io.Serial;

/**
 * The harness stops, saying why, as Python's {@code SystemExit} with a message did: a
 * service that never answered, say. The command line prints the reason alone and exits
 * with 1.
 */
public final class HarnessStopException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A stop.
	 * @param reason why, in a sentence
	 */
	public HarnessStopException(String reason) {
		super(reason);
	}

}
