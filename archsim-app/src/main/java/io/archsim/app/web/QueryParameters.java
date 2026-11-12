package io.archsim.app.web;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.archsim.app.store.Text;

/**
 * Query parameters read as FastAPI read them: a repeated parameter's last value counts,
 * lengths are counted in characters, a boolean is one of pydantic's words for one, in any
 * case, and a whole number is written as pydantic reads one. A controller takes each
 * parameter as {@code @RequestParam List<String>} and reads it here.
 */
public final class QueryParameters {

	private static final Set<String> TRUE = Set.of("1", "on", "t", "true", "y", "yes");

	private static final Set<String> FALSE = Set.of("0", "off", "f", "false", "n", "no");

	/**
	 * A whole number as pydantic reads one from text: white space around it, ASCII digits
	 * with single underscores between them, and a fraction of zeros. The group repeats
	 * possessively, so Java matches it in a loop: a greedy repeat would recurse once a
	 * group.
	 */
	private static final Pattern INTEGER = Pattern.compile("\\s*([+-]?[0-9]+(?:_[0-9]+)*+)(?:\\.0+)?\\s*",
			Pattern.UNICODE_CHARACTER_CLASS);

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
	 * @return the last value's meaning, or empty when it is not given
	 * @throws RequestInvalidException {@code 422} when it is no boolean
	 */
	public static Optional<Boolean> flag(@Nullable List<String> values, String name) {
		Optional<String> value = last(values);
		if (value.isEmpty()) {
			return Optional.empty();
		}
		String word = value.get().toLowerCase(Locale.ROOT);
		if (!TRUE.contains(word) && !FALSE.contains(word)) {
			throw RequestInvalidException.queryNotBoolean(name, value.get());
		}
		return Optional.of(TRUE.contains(word));
	}

	/**
	 * A word parameter, one of a few.
	 * @param values the parameter's values, if any
	 * @param name its name, for the refusal
	 * @param choices the words it may be
	 * @return the last value, or empty when it is not given
	 * @throws RequestInvalidException {@code 422} when it is another word
	 */
	public static Optional<String> choice(@Nullable List<String> values, String name, List<String> choices) {
		Optional<String> value = last(values);
		if (value.isPresent() && !choices.contains(value.get())) {
			throw RequestInvalidException.queryNotOneOf(name, value.get(), choices);
		}
		return value;
	}

	/**
	 * A whole-number parameter with no upper bound. A number past what a {@code long}
	 * holds, which pydantic takes, counts as the largest {@code long}.
	 * @param values the parameter's values, if any
	 * @param name its name, for the refusal
	 * @param minimum the least it may be
	 * @return the last value, or empty when it is not given
	 * @throws RequestInvalidException {@code 422} when it is no whole number or less
	 */
	public static OptionalLong integer(@Nullable List<String> values, String name, long minimum) {
		return integer(values, name, minimum, Long.MAX_VALUE);
	}

	/**
	 * A whole-number parameter.
	 * @param values the parameter's values, if any
	 * @param name its name, for the refusal
	 * @param minimum the least it may be
	 * @param maximum the most it may be; {@link Long#MAX_VALUE} for no bound
	 * @return the last value, or empty when it is not given
	 * @throws RequestInvalidException {@code 422} when it is no whole number or out of
	 * bounds
	 */
	public static OptionalLong integer(@Nullable List<String> values, String name, long minimum, long maximum) {
		Optional<String> value = last(values);
		if (value.isEmpty()) {
			return OptionalLong.empty();
		}
		Matcher number = INTEGER.matcher(value.get());
		if (!number.matches()) {
			throw RequestInvalidException.queryNotInteger(name, value.get());
		}
		BigInteger parsed = new BigInteger(number.group(1).replace("_", ""));
		if (parsed.compareTo(BigInteger.valueOf(minimum)) < 0) {
			throw RequestInvalidException.queryBelow(name, value.get(), minimum);
		}
		if (parsed.compareTo(BigInteger.valueOf(maximum)) > 0) {
			if (maximum != Long.MAX_VALUE) {
				throw RequestInvalidException.queryAbove(name, value.get(), maximum);
			}
			return OptionalLong.of(Long.MAX_VALUE);
		}
		return OptionalLong.of(parsed.longValueExact());
	}

	/**
	 * Parameters an endpoint needs.
	 * @param parameters each parameter's values by its name, in the endpoint's order
	 * @return each parameter's last value, in that order
	 * @throws RequestInvalidException {@code 422} listing every one not given
	 */
	public static List<String> required(SequencedMap<String, @Nullable List<String>> parameters) {
		List<String> missing = parameters.entrySet()
			.stream()
			.filter((parameter) -> last(parameter.getValue()).isEmpty())
			.map(Map.Entry::getKey)
			.toList();
		if (!missing.isEmpty()) {
			throw RequestInvalidException.queryMissing(missing);
		}
		return parameters.values().stream().map((values) -> last(values).orElseThrow()).toList();
	}

	private static Optional<String> last(@Nullable List<String> values) {
		return (values != null && !values.isEmpty()) ? Optional.of(values.getLast()) : Optional.empty();
	}

}
