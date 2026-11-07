package io.archsim.engine.calibration.mapping;

import java.util.ArrayList;
import java.util.List;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;

/**
 * What is wrong with an input, as the Python engine's {@code validation_message} words a
 * pydantic error for whoever sent it: each error as {@code where: what}, without the
 * input values pydantic puts in its own message.
 */
public final class ValidationMessages {

	/** The most errors listed; the rest are counted. */
	private static final int LIMIT = 10;

	private ValidationMessages() {
	}

	/**
	 * The errors, in one line.
	 * @param ex the input's errors
	 * @return the first ten errors as {@code where: what}, the top level as
	 * {@code (top level)}, then {@code ... and N more}, joined by {@code ; }
	 */
	public static String of(BindingException ex) {
		List<BindingError> errors = ex.errors();
		List<String> lines = new ArrayList<>();
		errors.stream().limit(LIMIT).forEach((error) -> lines.add(where(error) + ": " + error.message()));
		if (errors.size() > LIMIT) {
			lines.add("... and " + (errors.size() - LIMIT) + " more");
		}
		return String.join("; ", lines);
	}

	private static String where(BindingError error) {
		String location = error.location().toString();
		return location.isEmpty() ? "(top level)" : location;
	}

}
