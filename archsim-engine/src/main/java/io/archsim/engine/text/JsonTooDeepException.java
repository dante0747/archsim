package io.archsim.engine.text;

import java.io.Serial;

/**
 * JSON nested deeper than {@link PythonJsonReader} reads it. Python read it up to its C
 * recursion limit, about 3,000 levels, and raised {@code RecursionError} past that: not
 * text refused, as {@link JsonDecodeException} is, but a reader giving up.
 */
public final class JsonTooDeepException extends IllegalArgumentException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * JSON refused for its depth.
	 * @param message how deep is too deep
	 * @param cause the parser's exception
	 */
	JsonTooDeepException(String message, Throwable cause) {
		super(message, cause);
	}

}
