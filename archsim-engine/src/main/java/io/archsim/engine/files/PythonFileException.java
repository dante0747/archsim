package io.archsim.engine.files;

import java.io.Serial;
import java.util.Objects;

/**
 * A file that could not be used, in the words of the exception Python raised for it,
 * which the Python CLI printed after its own words ({@code Cannot calibrate: }) and the
 * MCP server answered with.
 */
public final class PythonFileException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * A file refused.
	 * @param message what Python's exception said
	 */
	public PythonFileException(String message) {
		super(Objects.requireNonNull(message, "message"));
	}

	/**
	 * A file refused for what reading it raised.
	 * @param message what Python's exception said
	 * @param cause what Java raised
	 */
	public PythonFileException(String message, Throwable cause) {
		super(Objects.requireNonNull(message, "message"), cause);
	}

	/**
	 * What Python's exception said.
	 * @return the message, never {@code null}
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage());
	}

}
