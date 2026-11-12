package io.archsim.engine.numeric;

/**
 * The text of a number as Python's {@code float()} and {@code int()} read it, once the
 * spaces around it are stripped: what is a number's text, and its digits in ASCII for
 * Java's parsers. Digits are any script's decimal digits, and ASCII letters in a base
 * above ten.
 */
final class NumberText {

	/** Base ten: a float's digits, and {@code int()}'s base by default. */
	static final int DECIMAL = 10;

	/** The first character past ASCII. */
	static final int ASCII_END = 0x80;

	private NumberText() {
	}

	/**
	 * Whether text is a decimal float as {@code float()} reads one: a sign maybe, digits
	 * with a point before, among or after them, and an exponent maybe, each run of digits
	 * with an underscore allowed between two of them. Scanned in one pass, because a
	 * regular expression's repeated group recurses once per digit in Java, and Python
	 * reads a number of any length.
	 * @param number the text, without the spaces around it
	 * @return {@code true} for a float's text
	 */
	static boolean isFloat(String number) {
		int start = skipSign(number, 0);
		int afterNumber = skipNumber(number, start);
		return afterNumber > start && skipExponent(number, afterNumber) == number.length();
	}

	private static int skipSign(String number, int from) {
		int sign = charAt(number, from);
		return (sign == '+' || sign == '-') ? from + 1 : from;
	}

	/**
	 * Where a number ends: digits with a point before, among or after them.
	 * @param number the text
	 * @param from where the number would start
	 * @return the index after it; {@code from} when none is there
	 */
	private static int skipNumber(String number, int from) {
		int afterWhole = skipDigits(number, from, DECIMAL);
		if (charAt(number, afterWhole) != '.') {
			return afterWhole;
		}
		int fractionStart = afterWhole + 1;
		int afterFraction = skipDigits(number, fractionStart, DECIMAL);
		boolean hasDigits = afterWhole > from || afterFraction > fractionStart;
		return hasDigits ? afterFraction : from;
	}

	/**
	 * Where an exponent ends: {@code e} or {@code E}, a sign maybe, and digits.
	 * @param number the text
	 * @param from where the exponent would start
	 * @return the index after it; {@code from} when none is there, or it has no digits
	 */
	private static int skipExponent(String number, int from) {
		int mark = charAt(number, from);
		if (mark != 'e' && mark != 'E') {
			return from;
		}
		int afterSign = skipSign(number, from + 1);
		int afterDigits = skipDigits(number, afterSign, DECIMAL);
		return (afterDigits > afterSign) ? afterDigits : from;
	}

	/**
	 * The UTF-16 unit at an index of a text.
	 * @param text the text
	 * @param index the index
	 * @return the unit; {@code -1} past the text's end
	 */
	private static int charAt(String text, int index) {
		return (index < text.length()) ? text.charAt(index) : -1;
	}

	/**
	 * Whether text is a whole number's digits in a base as {@code int()} reads them, an
	 * underscore allowed between two of them.
	 * @param digits the text, without its sign and prefix
	 * @param radix the base
	 * @return {@code true} for digits
	 */
	static boolean isInteger(String digits, int radix) {
		return !digits.isEmpty() && skipDigits(digits, 0, radix) == digits.length();
	}

	/**
	 * Where a run of digits of a base ends: a digit, then digits, each maybe after one
	 * underscore.
	 * @param number the text
	 * @param from where the run would start
	 * @param radix the base
	 * @return the index after it; {@code from} when no digit is there
	 */
	private static int skipDigits(String number, int from, int radix) {
		int at = from;
		while (at < number.length()) {
			int codePoint = number.codePointAt(at);
			if (digit(codePoint, radix) >= 0) {
				at += Character.charCount(codePoint);
			}
			else if (codePoint == '_' && at > from && isDigitAt(number, at + 1, radix)) {
				at++;
			}
			else {
				break;
			}
		}
		return at;
	}

	private static boolean isDigitAt(String number, int index, int radix) {
		return index < number.length() && digit(number.codePointAt(index), radix) >= 0;
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
	 * A number with its digits in ASCII and without underscores.
	 * @param number the number, as written
	 * @param radix its base
	 * @return its text for Java's parsers
	 */
	static String ascii(String number, int radix) {
		StringBuilder ascii = new StringBuilder(number.length());
		number.codePoints().filter((codePoint) -> codePoint != '_').forEach((codePoint) -> {
			int digit = digit(codePoint, radix);
			ascii.append((digit >= 0) ? Character.forDigit(digit, radix) : (char) codePoint);
		});
		return ascii.toString();
	}

}
