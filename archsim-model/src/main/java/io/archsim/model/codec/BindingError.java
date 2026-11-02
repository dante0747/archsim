package io.archsim.model.codec;

import java.util.Objects;

/**
 * One thing wrong with a JSON input, as pydantic reports it: the kind of error, where it
 * is and what it says. Messages are pydantic's word for word (for example
 * {@code "Field required"} or {@code "Input should be greater than or equal to 1"}),
 * because users see them.
 *
 * @param type pydantic's error type, such as {@code missing} or
 * {@code greater_than_equal}
 * @param location where in the input the error is
 * @param message pydantic's message
 */
public record BindingError(String type, Location location, String message) {

	public BindingError {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(location, "location");
		Objects.requireNonNull(message, "message");
	}
}
