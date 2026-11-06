package io.archsim.model.text;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

/**
 * How Python turns values into text, where ArchSim's messages and contract inherited it:
 * a float's {@code repr}, {@code str()} of a value read from JSON, and
 * {@code str.title()}.
 *
 * <p>
 * The design document and its messages were defined by a Python implementation, so a
 * number in a message reads {@code 1e-05} or {@code 2.5}, never {@code 1.0E-5}
 * (ADR-0027).
 */
public final class PythonText {

	/**
	 * A float's {@code repr} is in fixed notation only when its decimal point's place in
	 * its digits is above this ({@code 0.0001}, but {@code 1e-05}).
	 */
	private static final int FIXED_NOTATION_POINT_ABOVE = -4;

	/**
	 * A float's {@code repr} is in fixed notation only when its decimal point's place in
	 * its digits is at most this ({@code 1000000000000000.0}, but {@code 1e+16}).
	 */
	private static final int FIXED_NOTATION_POINT_UP_TO = 16;

	/** The first character that is not a C0 control character. */
	private static final int SPACE = 0x20;

	/** The DEL control character, which {@code repr} escapes too. */
	private static final int DELETE = 0x7f;

	private static final Map<Integer, String> ESCAPES = Map.of((int) '\\', "\\\\", (int) '\n', "\\n", (int) '\r', "\\r",
			(int) '\t', "\\t");

	private PythonText() {
	}

	/**
	 * Python's {@code repr(x)} of a float: the shortest digits that read back as
	 * {@code x}, in fixed notation from 1e-4 up to 1e16 ({@code 0.0001}, {@code 2.5},
	 * {@code 100.0}) and in scientific notation outside it ({@code 1e-05},
	 * {@code 1e+16}).
	 * @param number the float
	 * @return its repr
	 */
	public static String repr(double number) {
		if (Double.isNaN(number)) {
			return "nan";
		}
		if (Double.isInfinite(number)) {
			return (number > 0) ? "inf" : "-inf";
		}
		if (number == 0) {
			return (Double.doubleToRawLongBits(number) < 0) ? "-0.0" : "0.0";
		}
		String sign = (number < 0) ? "-" : "";
		return sign + reprOfMagnitude(Math.abs(number));
	}

	/**
	 * Python's {@code repr()} of the value {@code json.loads} gives for this JSON: as
	 * {@link #str(JsonNode)}, with strings quoted ({@code 'a'}).
	 * @param value the JSON value
	 * @return its repr
	 */
	public static String repr(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return "None";
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? "True" : "False";
		}
		if (value.isIntegralNumber()) {
			return value.bigIntegerValue().toString();
		}
		if (value.isNumber()) {
			return repr(value.doubleValue());
		}
		if (value.isString()) {
			return repr(value.stringValue());
		}
		if (value.isArray()) {
			return '[' + value.valueStream().map(PythonText::repr).collect(Collectors.joining(", ")) + ']';
		}
		return '{' + value.propertyStream()
			.map((property) -> repr(property.getKey()) + ": " + repr(property.getValue()))
			.collect(Collectors.joining(", ")) + '}';
	}

	/**
	 * Python's {@code repr()} of a string: in single quotes, or in double quotes when it
	 * holds a single quote and no double quote, with backslashes and control characters
	 * escaped.
	 * @param text the string
	 * @return its repr
	 */
	public static String repr(String text) {
		char quote = (text.indexOf('\'') >= 0 && text.indexOf('"') < 0) ? '"' : '\'';
		StringBuilder out = new StringBuilder().append(quote);
		text.codePoints().forEach((codePoint) -> appendEscaped(out, codePoint, quote));
		return out.append(quote).toString();
	}

	/**
	 * Python's {@code str()} of an integer read from JSON: its digits.
	 * @param number the integer
	 * @return its digits, after a minus sign when it is negative
	 */
	public static String str(long number) {
		return Long.toString(number);
	}

	/**
	 * Python's {@code str()} of the value {@code json.loads} gives for this JSON:
	 * {@code None}, {@code True}, {@code 1.5}, a string as it is, {@code ['a', 1]},
	 * {@code {'a': 1}}.
	 * @param value the JSON value
	 * @return its text
	 */
	public static String str(JsonNode value) {
		return value.isString() ? value.stringValue() : repr(value);
	}

	/**
	 * Python's {@code str.title()}: each word starts with a capital and goes on in lower
	 * case, a word being a run of cased letters ({@code "latencyP95Ms"} becomes
	 * {@code "Latencyp95Ms"}).
	 * @param text the string
	 * @return the string in title case
	 */
	public static String title(String text) {
		StringBuilder out = new StringBuilder(text.length());
		boolean previousCased = false;
		for (int i = 0; i < text.length();) {
			int codePoint = text.codePointAt(i);
			boolean cased = Character.isUpperCase(codePoint) || Character.isLowerCase(codePoint)
					|| Character.isTitleCase(codePoint);
			out.appendCodePoint(previousCased ? Character.toLowerCase(codePoint) : Character.toTitleCase(codePoint));
			previousCased = cased;
			i += Character.charCount(codePoint);
		}
		return out.toString();
	}

	/**
	 * {@code bool(value)} of the value {@code json.loads} gives for this JSON.
	 * @param value the JSON value
	 * @return whether Python counts the value as true
	 */
	public static boolean truthy(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return false;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isIntegralNumber()) {
			// exactly: a whole number beyond a double's range has no double
			return value.bigIntegerValue().signum() != 0;
		}
		if (value.isNumber()) {
			return value.doubleValue() != 0;
		}
		if (value.isString()) {
			return !value.stringValue().isEmpty();
		}
		return !value.isEmpty();
	}

	/**
	 * {@code e+16}, {@code e-05}: a sign and at least two digits, as C's printf writes
	 * them.
	 * @param exponent the power of ten
	 * @return the exponent's text
	 */
	static String exponent(int exponent) {
		return String.format(Locale.ROOT, "e%+03d", exponent);
	}

	/**
	 * The repr of a positive finite float.
	 * @param magnitude the float, above zero
	 * @return its repr
	 */
	private static String reprOfMagnitude(double magnitude) {
		// Double.toString gives the shortest digits that round-trip, like Python's repr
		BigDecimal shortest = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
		String digits = shortest.unscaledValue().toString();
		// the decimal point's place in the digits
		int point = digits.length() - shortest.scale();
		if (point > FIXED_NOTATION_POINT_ABOVE && point <= FIXED_NOTATION_POINT_UP_TO) {
			return fixed(digits, point);
		}
		String mantissa = (digits.length() == 1) ? digits : digits.charAt(0) + "." + digits.substring(1);
		return mantissa + exponent(point - 1);
	}

	private static String fixed(String digits, int point) {
		if (point <= 0) {
			return "0." + "0".repeat(-point) + digits;
		}
		if (point >= digits.length()) {
			return digits + "0".repeat(point - digits.length()) + ".0";
		}
		return digits.substring(0, point) + "." + digits.substring(point);
	}

	private static void appendEscaped(StringBuilder out, int codePoint, char quote) {
		String escape = ESCAPES.get(codePoint);
		if (escape != null) {
			out.append(escape);
		}
		else if (codePoint == quote) {
			out.append('\\').append(quote);
		}
		else if (codePoint < SPACE || codePoint == DELETE) {
			out.append("\\x%02x".formatted(codePoint));
		}
		else {
			out.appendCodePoint(codePoint);
		}
	}

}
