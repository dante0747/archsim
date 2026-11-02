package io.archsim.model.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How pydantic's lax mode reads numbers out of strings: {@code "1.5"}, {@code " 2 "} and
 * {@code "1_000"} are numbers, {@code "inf"} and {@code "nan"} floats, {@code "5.0"} the integer 5.
 */
final class LaxNumbers {

    private static final String DIGITS = "\\d+(?:_\\d+)*";
    private static final Pattern FLOAT = Pattern.compile(
            "[+-]?(?:" + DIGITS + "(?:\\." + "(?:" + DIGITS + ")?)?|\\." + DIGITS + ")(?:[eE][+-]?" + DIGITS + ")?");
    private static final Pattern INTEGER = Pattern.compile("[+-]?" + DIGITS + "(?:\\.0*)?");

    private LaxNumbers() {}

    /** Python's {@code float(s)}; empty when it would raise. */
    static Optional<Double> parseFloat(String text) {
        String s = text.strip();
        String word = s.toLowerCase(Locale.ROOT).replaceFirst("^[+-]", "");
        if (word.equals("inf") || word.equals("infinity")) {
            return Optional.of(s.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        }
        if (word.equals("nan")) {
            return Optional.of(Double.NaN);
        }
        if (!FLOAT.matcher(s).matches()) {
            return Optional.empty();
        }
        return Optional.of(Double.parseDouble(s.replace("_", "")));
    }

    /** pydantic's integer from a string: digits, maybe a sign and a {@code .0}; empty otherwise. */
    static Optional<BigInteger> parseInteger(String text) {
        String s = text.strip();
        if (!INTEGER.matcher(s).matches()) {
            return Optional.empty();
        }
        int point = s.indexOf('.');
        String digits = (point < 0 ? s : s.substring(0, point)).replace("_", "");
        return Optional.of(new BigDecimal(digits).toBigIntegerExact());
    }
}
