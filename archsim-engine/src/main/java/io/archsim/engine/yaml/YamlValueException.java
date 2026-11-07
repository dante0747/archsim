package io.archsim.engine.yaml;

/**
 * YAML Python refuses with a {@code ValueError}: a number or date it cannot read, or a
 * text beyond the bounds YAML from anyone is read within. The message is Python's.
 */
public final class YamlValueException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * A value Python refuses.
	 * @param message what is wrong, in Python's words
	 */
	YamlValueException(String message) {
		super(message);
	}

	/**
	 * A value Python refuses, as another exception showed it.
	 * @param message what is wrong, in Python's words
	 * @param cause what showed it
	 */
	YamlValueException(String message, Throwable cause) {
		super(message, cause);
	}

}
