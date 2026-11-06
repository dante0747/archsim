package io.archsim.engine.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.archsim.model.text.PythonText;

/**
 * Numbers read from text as Python's {@code float()} and {@code int()} read them: around
 * whitespace, with underscores between digits and any script's decimal digits, and
 * refused in Python's words.
 */
public final class PythonNumbers {

	private static final Pattern FLOAT = Pattern.compile(
			"[+-]?(?:(?:\\d(?:_?\\d)*)(?:\\.(?:\\d(?:_?\\d)*)?)?|\\.\\d(?:_?\\d)*)(?:[eE][+-]?\\d(?:_?\\d)*)?",
			Pattern.UNICODE_CHARACTER_CLASS);

	private static final Pattern SPECIAL = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

	/** The first character past ASCII. */
	private static final int ASCII_END = 0x80;

	/** The prefix a base's digits may start with, by base. */
	private static final Map<Integer, String> PREFIXES = Map.of(2, "0b", 8, "0o", 16, "0x");

	/** The radix of the digits Python reads. */
	private static final int DECIMAL = 10;

	private PythonNumbers() {
	}

	/**
	 * A float, as {@code float(text)} reads it.
	 * @param text the text
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a float
	 */
	public static double parseFloat(String text) {
		String number = text.strip();
		if (SPECIAL.matcher(number).matches()) {
			return special(number);
		}
		if (!FLOAT.matcher(number).matches()) {
			throw new NumberFormatException("could not convert string to float: " + PythonText.repr(text));
		}
		return Double.parseDouble(ascii(number));
	}

	/**
	 * A whole number, as {@code int(text)} reads it in base 10.
	 * @param text the text
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a whole number
	 */
	public static BigInteger parseInt(String text) {
		return parseInt(text, DECIMAL);
	}

	/**
	 * A whole number, as {@code int(text, radix)} reads it: its base's prefix
	 * ({@code 0b}, {@code 0o}, {@code 0x}) allowed.
	 * @param text the text
	 * @param radix the base: 2, 8, 10 or 16
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a whole number in
	 * the base
	 */
	public static BigInteger parseInt(String text, int radix) {
		String number = text.strip();
		boolean negative = number.startsWith("-");
		if (negative || number.startsWith("+")) {
			number = number.substring(1);
		}
		String prefix = PREFIXES.getOrDefault(radix, "");
		if (!prefix.isEmpty() && number.toLowerCase(Locale.ROOT).startsWith(prefix)) {
			number = number.substring(prefix.length());
			if (number.startsWith("_")) {
				number = number.substring(1);
			}
		}
		if (!isDigits(number, radix)) {
			throw new NumberFormatException(
					"invalid literal for int() with base " + radix + ": " + PythonText.repr(text));
		}
		BigInteger value = new BigInteger(ascii(number, radix), radix);
		return negative ? value.negate() : value;
	}

	/**
	 * A float truncated to a whole number, as {@code int(x)} truncates it.
	 * @param value the float
	 * @return the whole number toward zero
	 * @throws NumberFormatException with Python's words for NaN or an infinity
	 */
	public static BigInteger truncate(double value) {
		if (Double.isNaN(value)) {
			throw new NumberFormatException("cannot convert float NaN to integer");
		}
		if (Double.isInfinite(value)) {
			throw new NumberFormatException("cannot convert float infinity to integer");
		}
		return new BigDecimal(value).toBigInteger();
	}

	private static double special(String number) {
		String word = number.toLowerCase(Locale.ROOT);
		if (word.endsWith("nan")) {
			return Double.NaN;
		}
		return word.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
	}

	/**
	 * Whether text is digits of a base, an underscore allowed between two of them.
	 * @param digits the text
	 * @param radix the base
	 * @return {@code true} for digits
	 */
	private static boolean isDigits(String digits, int radix) {
		if (digits.isEmpty() || digits.startsWith("_") || digits.endsWith("_") || digits.contains("__")) {
			return false;
		}
		return digits.codePoints().allMatch((codePoint) -> codePoint == '_' || digit(codePoint, radix) >= 0);
	}

	/**
	 * A digit's value in a base, as {@code int()} reads it: any script's decimal digits,
	 * and ASCII letters, but not the letters of other scripts.
	 * @param codePoint the character
	 * @param radix the base
	 * @return its value; {@code -1} when it is no digit of the base
	 */
	private static int digit(int codePoint, int radix) {
		int value;
		if (Character.isDigit(codePoint)) {
			value = Character.digit(codePoint, DECIMAL);
		}
		else {
			value = (codePoint < ASCII_END) ? Character.digit(codePoint, radix) : -1;
		}
		return (value < radix) ? value : -1;
	}

	/**
	 * A number with its decimal digits in ASCII and without underscores.
	 * @param number the number, as written
	 * @return its text for Java's parsers
	 */
	private static String ascii(String number) {
		return ascii(number, DECIMAL);
	}

	/**
	 * A number with its digits in ASCII and without underscores.
	 * @param number the number, as written
	 * @param radix its base
	 * @return its text for Java's parsers
	 */
	private static String ascii(String number, int radix) {
		StringBuilder ascii = new StringBuilder(number.length());
		number.codePoints().filter((codePoint) -> codePoint != '_').forEach((codePoint) -> {
			int digit = digit(codePoint, radix);
			ascii.append((digit >= 0) ? Character.forDigit(digit, radix) : (char) codePoint);
		});
		return ascii.toString();
	}

}
