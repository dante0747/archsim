package io.archsim.engine.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.archsim.engine.text.PythonStrings;
import io.archsim.model.text.PythonText;

/**
 * Numbers read from text as Python's {@code float()} and {@code int()} read them: around
 * whitespace, with underscores between digits and any script's decimal digits, and
 * refused in Python's words.
 */
public final class PythonNumbers {

	private static final Pattern SPECIAL = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

	/**
	 * The most digits Python converts in a base that is not a power of two
	 * ({@code sys.int_info.default_max_str_digits}): its guard against conversions that
	 * take quadratic time.
	 */
	private static final int MAX_DIGITS = 4300;

	/** The prefix a base's digits may start with, by base. */
	private static final Map<Integer, String> PREFIXES = Map.of(2, "0b", 8, "0o", 16, "0x");

	/**
	 * The most characters of a text's repr that {@code int()}'s refusal quotes: CPython
	 * words it with {@code %.200R}.
	 */
	private static final int QUOTED_CHARACTERS = 200;

	/**
	 * The ASCII whitespace C's {@code isspace()} accepts, which CPython strips from a
	 * number's text once it has turned the spaces beyond ASCII into spaces.
	 */
	private static final String ASCII_SPACES = " \t\n\u000b\f\r";

	private PythonNumbers() {
	}

	/**
	 * A float, as {@code float(text)} reads it.
	 * @param text the text
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a float
	 */
	public static double parseFloat(String text) {
		String number = stripSpaces(text);
		if (SPECIAL.matcher(number).matches()) {
			return special(number);
		}
		if (!NumberText.isFloat(number)) {
			throw new NumberFormatException("could not convert string to float: " + PythonText.repr(text));
		}
		return Double.parseDouble(NumberText.ascii(number, NumberText.DECIMAL));
	}

	/**
	 * A whole number, as {@code int(text)} reads it in base 10.
	 * @param text the text
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a whole number
	 */
	public static BigInteger parseInt(String text) {
		return parseInt(text, NumberText.DECIMAL);
	}

	/**
	 * A whole number, as {@code int(text, radix)} reads it: its base's prefix
	 * ({@code 0b}, {@code 0o}, {@code 0x}) allowed.
	 * @param text the text
	 * @param radix the base: 2, 8, 10 or 16
	 * @return its value
	 * @throws NumberFormatException with Python's words when it is not a whole number in
	 * the base, or has more decimal digits than Python converts
	 */
	public static BigInteger parseInt(String text, int radix) {
		String number = stripSpaces(text);
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
		if (!NumberText.isInteger(number, radix)) {
			throw new NumberFormatException(
					"invalid literal for int() with base " + radix + ": " + quoted(PythonText.repr(text)));
		}
		BigInteger value = fromDigits(NumberText.ascii(number, radix), radix);
		return negative ? value.negate() : value;
	}

	/**
	 * A float truncated to a whole number, as {@code int(x)} truncates it.
	 * @param value the float
	 * @return the whole number toward zero
	 * @throws NumberFormatException with Python's words for NaN or an infinity
	 */
	public static BigInteger truncate(double value) {
		requireFinite(value);
		return new BigDecimal(value).toBigInteger();
	}

	/**
	 * A float rounded to a whole number, halves to even, as {@code round(x)} rounds it.
	 * @param value the float
	 * @return the nearest whole number
	 * @throws NumberFormatException with Python's words for NaN or an infinity
	 */
	public static BigInteger round(double value) {
		requireFinite(value);
		return new BigDecimal(value).setScale(0, RoundingMode.HALF_EVEN).toBigIntegerExact();
	}

	/**
	 * A whole number as a float, as {@code float(n)} converts it: the nearest float.
	 * @param value the whole number
	 * @return its float
	 * @throws NumberFormatException with Python's words when it is beyond a float's range
	 */
	public static double toFloat(BigInteger value) {
		double converted = value.doubleValue();
		if (Double.isInfinite(converted)) {
			throw new NumberFormatException("int too large to convert to float");
		}
		return converted;
	}

	/**
	 * {@code n / divisor} of two whole numbers, as Python's true division gives it: the
	 * float nearest the exact quotient.
	 * @param value the whole number divided
	 * @param divisor a whole number whose quotients end, such as 100 or 1024
	 * @return the quotient
	 * @throws NumberFormatException with Python's words when it is beyond a float's range
	 */
	public static double divide(BigInteger value, BigDecimal divisor) {
		double quotient = new BigDecimal(value).divide(divisor).doubleValue();
		if (Double.isInfinite(quotient)) {
			throw new NumberFormatException("integer division result too large for a float");
		}
		return quotient;
	}

	/**
	 * Refuses a float that is not finite, as Python's conversions to a whole number do.
	 * @param value the float
	 * @throws NumberFormatException with Python's words for NaN or an infinity
	 */
	private static void requireFinite(double value) {
		if (Double.isNaN(value)) {
			throw new NumberFormatException("cannot convert float NaN to integer");
		}
		if (Double.isInfinite(value)) {
			throw new NumberFormatException("cannot convert float infinity to integer");
		}
	}

	private static double special(String number) {
		String word = number.toLowerCase(Locale.ROOT);
		if (word.endsWith("nan")) {
			return Double.NaN;
		}
		return word.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
	}

	/**
	 * The value of a base's digits, as Python converts them.
	 * @param digits ASCII digits of the base
	 * @param radix the base: 2, 8, 10 or 16
	 * @return their value
	 * @throws NumberFormatException with Python's words for more decimal digits than it
	 * converts
	 */
	private static BigInteger fromDigits(String digits, int radix) {
		if (Integer.bitCount(radix) == 1) {
			return powerOfTwo(digits, radix);
		}
		if (digits.length() > MAX_DIGITS) {
			throw new NumberFormatException(
					"Exceeds the limit (" + MAX_DIGITS + " digits) for integer string conversion: value has "
							+ digits.length() + " digits; use sys.set_int_max_str_digits() to increase the limit");
		}
		return new BigInteger(digits, radix);
	}

	/**
	 * Digits of a base that is a power of two, read in time linear in their number, as
	 * Python reads them, which sets no limit on them.
	 * @param digits ASCII digits of the base
	 * @param radix the base: 2, 8 or 16
	 * @return their value
	 */
	private static BigInteger powerOfTwo(String digits, int radix) {
		int bitsPerDigit = Integer.numberOfTrailingZeros(radix);
		byte[] bytes = new byte[(digits.length() * bitsPerDigit + Byte.SIZE - 1) / Byte.SIZE];
		int pending = 0;
		int pendingBits = 0;
		int next = bytes.length;
		for (int i = digits.length() - 1; i >= 0; i--) {
			pending |= Character.digit(digits.charAt(i), radix) << pendingBits;
			pendingBits += bitsPerDigit;
			while (pendingBits >= Byte.SIZE) {
				bytes[--next] = (byte) pending;
				pending >>>= Byte.SIZE;
				pendingBits -= Byte.SIZE;
			}
		}
		if (pendingBits > 0) {
			bytes[--next] = (byte) pending;
		}
		return new BigInteger(1, bytes);
	}

	/**
	 * A number's text without the whitespace CPython strips from its ends: ASCII's, and
	 * beyond ASCII what {@code str.isspace()} accepts, but not the information separators
	 * {@code 0x1c} to {@code 0x1f}, which are whitespace to Python's strings.
	 * @param text the text
	 * @return the text stripped
	 */
	private static String stripSpaces(String text) {
		int start = 0;
		int end = text.length();
		while (start < end && isStrippedSpace(text.charAt(start))) {
			start++;
		}
		while (end > start && isStrippedSpace(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(start, end);
	}

	private static boolean isStrippedSpace(char character) {
		return (character < NumberText.ASCII_END) ? ASCII_SPACES.indexOf(character) >= 0
				: PythonStrings.isSpace(character);
	}

	/**
	 * A repr as {@code %.200R} quotes it: its first 200 characters, which may end inside
	 * an escape or before the closing quote.
	 * @param repr the repr
	 * @return its first characters
	 */
	private static String quoted(String repr) {
		if (repr.codePointCount(0, repr.length()) <= QUOTED_CHARACTERS) {
			return repr;
		}
		return repr.substring(0, repr.offsetByCodePoints(0, QUOTED_CHARACTERS));
	}

}
