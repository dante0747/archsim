package io.archsim.parity;

import java.io.Serial;

import tools.jackson.databind.JsonNode;

/**
 * The error the Python engine raises where a Java function throws, as a golden file
 * records it: {@code {type, message}} and maybe more, with the Python exception's type
 * name. A module's parity calls translate the exceptions the golden files record into
 * this one, with the Java exception as its cause, so that {@link Parity} compares the
 * two.
 */
public final class PythonError extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Not serialized: the recorded error belongs to the test that compares it. */
	private final transient JsonNode error;

	/**
	 * Creates the Python engine's error for a Java exception.
	 * @param error the error as a golden file records it
	 * @param cause the Java exception in its place
	 */
	public PythonError(JsonNode error, RuntimeException cause) {
		super(error.toString(), cause);
		this.error = error;
	}

	/** The error as a golden file records it. */
	public JsonNode error() {
		return this.error;
	}

}
