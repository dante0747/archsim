package io.archsim.model.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How pydantic's lax mode reads numbers out of strings: {@code "1.5"}, {@code " 2 "} and
 * {@code "1_000"} are numbers, {@code "inf"} and {@code "nan"} floats, {@code "5.0"} the
 * integer 5.
 */
final class LaxNumbers {

	/**
	 * Digits, an underscore allowed between two of them. The group repeats possessively,
	 * so Java matches it in a loop: a greedy repeat would recurse once a group. Nothing
	 * after it starts with a digit or an underscore, so it matches what a greedy repeat
	 * would.
	 */
	private static final String DIGITS = "\\d+(?:_\\d+)*+";

	private static final Pattern FLOAT = Pattern.compile(
			"[+-]?(?:" + DIGITS + "(?:\\." + "(?:" + DIGITS + ")?)?|\\." + DIGITS + ")(?:[eE][+-]?" + DIGITS + ")?");

	private static final Pattern INTEGER = Pattern.compile("[+-]?" + DIGITS + "(?:\\.0*)?");

	private LaxNumbers() {
	}

	/**
	 * Python's {@code float(text)}; empty when it would raise.
	 * @param text the string read
	 * @return the float, or empty when the string is not one
	 */
	static Optional<Double> parseFloat(String text) {
		String stripped = text.strip();
		String word = stripped.toLowerCase(Locale.ROOT).replaceFirst("^[+-]", "");
		if ("inf".equals(word) || "infinity".equals(word)) {
			return Optional.of(stripped.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
		}
		if ("nan".equals(word)) {
			return Optional.of(Double.NaN);
		}
		if (!FLOAT.matcher(stripped).matches()) {
			return Optional.empty();
		}
		return Optional.of(Double.parseDouble(stripped.replace("_", "")));
	}

	/**
	 * pydantic's integer from a string: digits, maybe a sign and a {@code .0}; empty
	 * otherwise.
	 * @param text the string read
	 * @return the integer, or empty when the string is not one
	 */
	static Optional<BigInteger> parseInteger(String text) {
		String stripped = text.strip();
		if (!INTEGER.matcher(stripped).matches()) {
			return Optional.empty();
		}
		int point = stripped.indexOf('.');
		String digits = ((point < 0) ? stripped : stripped.substring(0, point)).replace("_", "");
		return Optional.of(new BigDecimal(digits).toBigIntegerExact());
	}

}
