package io.archsim.engine.text;

import java.io.Serial;

import org.jspecify.annotations.Nullable;

/**
 * Text that is not JSON, as Python's {@code json.loads} refuses it
 * ({@code JSONDecodeError}, a {@code ValueError}): the problem and where, such as
 * "Expecting value: line 1 column 1 (char 0)".
 */
public final class JsonDecodeException extends IllegalArgumentException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Text refused.
	 * @param message the problem and where, in {@code json}'s form
	 * @param cause the parser's exception, if any
	 */
	JsonDecodeException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}

}
