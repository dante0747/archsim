package io.archsim.app.web;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.app.store.Text;

/**
 * Query parameters read as FastAPI read them: a repeated parameter's last value counts,
 * lengths are counted in characters, and a boolean is one of pydantic's words for one, in
 * any case. A controller takes each parameter as {@code @RequestParam List<String>} and
 * reads it here.
 */
public final class QueryParameters {

	private static final Set<String> TRUE = Set.of("1", "on", "t", "true", "y", "yes");

	private static final Set<String> FALSE = Set.of("0", "off", "f", "false", "n", "no");

	private QueryParameters() {
	}

	/**
	 * A text parameter.
	 * @param values the parameter's values, if any
	 * @param name its name, for the refusal
	 * @param maxLength the most characters it may have
	 * @return the last value, or empty when it is not given
	 * @throws RequestInvalidException {@code 422} when it is longer
	 */
	public static Optional<String> text(@Nullable List<String> values, String name, int maxLength) {
		Optional<String> value = last(values);
		if (value.isPresent() && Text.length(value.get()) > maxLength) {
			throw RequestInvalidException.queryTooLong(name, value.get(), maxLength);
		}
		return value;
	}

	/**
	 * A boolean parameter.
	 * @param values the parameter's values, if any
	 * @param name its name, for the refusal
	 * @return the last value's meaning, {@code false} when it is not given
	 * @throws RequestInvalidException {@code 422} when it is no boolean
	 */
	public static boolean flag(@Nullable List<String> values, String name) {
		Optional<String> value = last(values);
		if (value.isEmpty()) {
			return false;
		}
		String word = value.get().toLowerCase(Locale.ROOT);
		if (!TRUE.contains(word) && !FALSE.contains(word)) {
			throw RequestInvalidException.queryNotBoolean(name, value.get());
		}
		return TRUE.contains(word);
	}

	private static Optional<String> last(@Nullable List<String> values) {
		return (values != null && !values.isEmpty()) ? Optional.of(values.getLast()) : Optional.empty();
	}

}
