package io.archsim.cli;

import java.io.Serial;
import java.util.Objects;

/**
 * A command that cannot go on, for a reason its user can act on (a file it cannot read,
 * text that is not JSON): {@code archsim} prints the message on standard error, without a
 * stack trace, and exits with code 1, as an uncaught error of the Python CLI did.
 */
final class CommandException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A command refused.
	 * @param message what went wrong, in words the user can act on
	 */
	CommandException(String message) {
		super(Objects.requireNonNull(message, "message"));
	}

	/**
	 * What went wrong.
	 * @return the message, never {@code null}
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

}
