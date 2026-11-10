package io.archsim.cli;

import java.io.Serial;
import java.util.Objects;

/**
 * A baseline {@code check --baseline-ref} cannot read from git, in the Python CLI's
 * words: a design outside a repository, a ref that cannot be read, git not installed.
 */
final class GitBaselineException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A baseline refused.
	 * @param message why, as the Python CLI said it
	 */
	GitBaselineException(String message) {
		super(Objects.requireNonNull(message, "message"));
	}

	/**
	 * A baseline refused for what running git raised.
	 * @param message why, as the Python CLI said it
	 * @param cause what Java raised
	 */
	GitBaselineException(String message, Throwable cause) {
		super(Objects.requireNonNull(message, "message"), cause);
	}

	/**
	 * Why the baseline cannot be read.
	 * @return the message, never {@code null}
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

}
