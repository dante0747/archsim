package io.archsim.model.codec;

import java.io.Serial;
import java.util.List;

/** A JSON input that does not fit its record: every error found, in pydantic's order. */
public final class BindingException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	/** Not serialized: the errors belong to the process that bound the input. */
	private final transient List<BindingError> errors;

	public BindingException(List<BindingError> errors) {
		super(summary(errors));
		this.errors = List.copyOf(errors);
	}

	/**
	 * The errors, in the order pydantic reports them: fields in declaration order, then
	 * extras.
	 */
	public List<BindingError> errors() {
		return errors;
	}

	private static String summary(List<BindingError> errors) {
		if (errors.isEmpty()) {
			throw new IllegalArgumentException("a binding exception needs at least one error");
		}
		BindingError first = errors.getFirst();
		String more = errors.size() > 1 ? " (+%d more)".formatted(errors.size() - 1) : "";
		return "%s: %s%s".formatted(first.location(), first.message(), more);
	}

}
