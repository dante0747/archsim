package io.archsim.model.text;

import java.math.BigDecimal;
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

	private PythonText() {
	}

	/**
	 * Python's {@code repr(x)} of a float: the shortest digits that read back as
	 * {@code x}, in fixed notation from 1e-4 up to 1e16 ({@code 0.0001}, {@code 2.5},
	 * {@code 100.0}) and in scientific notation outside it ({@code 1e-05},
	 * {@code 1e+16}).
	 */
	public static String repr(double x) {
		if (Double.isNaN(x)) {
			return "nan";
		}
		if (Double.isInfinite(x)) {
			return x > 0 ? "inf" : "-inf";
		}
		if (x == 0) {
			return Double.doubleToRawLongBits(x) < 0 ? "-0.0" : "0.0";
		}
		// Double.toString gives the shortest digits that round-trip, as Python's repr
		// does
		BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
		String digits = shortest.unscaledValue().toString();
		int point = digits.length() - shortest.scale(); // the decimal point's place in
														// the digits
		String sign = x < 0 ? "-" : "";
		if (point > -4 && point <= 16) {
			return sign + fixed(digits, point);
		}
		String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
		return sign + mantissa + exponent(point - 1);
	}

	/** Python's {@code str()} of an integer read from JSON: its digits. */
	public static String str(long x) {
		return Long.toString(x);
	}

	/**
	 * Python's {@code str()} of the value {@code json.loads} gives for this JSON:
	 * {@code None}, {@code True}, {@code 1.5}, a string as it is, {@code ['a', 1]},
	 * {@code {'a': 1}}.
	 */
	public static String str(JsonNode value) {
		return value.isString() ? value.stringValue() : repr(value);
	}

	/**
	 * Python's {@code repr()} of the value {@code json.loads} gives for this JSON: as
	 * {@link #str(JsonNode)}, with strings quoted ({@code 'a'}).
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
			return value.valueStream().map(PythonText::repr).collect(Collectors.joining(", ", "[", "]"));
		}
		return value.propertyStream()
			.map(e -> repr(e.getKey()) + ": " + repr(e.getValue()))
			.collect(Collectors.joining(", ", "{", "}"));
	}

	/**
	 * Python's {@code repr()} of a string: in single quotes, or in double quotes when it
	 * holds a single quote and no double quote, with backslashes and control characters
	 * escaped.
	 */
	public static String repr(String s) {
		char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
		StringBuilder out = new StringBuilder().append(quote);
		s.codePoints().forEach(c -> {
			String escape = ESCAPES.get(c);
			if (escape != null) {
				out.append(escape);
			}
			else if (c == quote) {
				out.append('\\').append(quote);
			}
			else if (c < 0x20 || c == 0x7f) {
				out.append("\\x%02x".formatted(c));
			}
			else {
				out.appendCodePoint(c);
			}
		});
		return out.append(quote).toString();
	}

	/**
	 * Python's {@code str.title()}: each word starts with a capital and goes on in lower
	 * case, a word being a run of cased letters ({@code "latencyP95Ms"} becomes
	 * {@code "Latencyp95Ms"}).
	 */
	public static String title(String s) {
		StringBuilder out = new StringBuilder(s.length());
		boolean previousCased = false;
		for (int i = 0; i < s.length();) {
			int c = s.codePointAt(i);
			boolean cased = Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c);
			out.appendCodePoint(previousCased ? Character.toLowerCase(c) : Character.toTitleCase(c));
			previousCased = cased;
			i += Character.charCount(c);
		}
		return out.toString();
	}

	/** {@code bool(value)} of the value {@code json.loads} gives for this JSON. */
	public static boolean truthy(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return false;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isNumber()) {
			return value.doubleValue() != 0;
		}
		if (value.isString()) {
			return !value.stringValue().isEmpty();
		}
		return !value.isEmpty();
	}

	private static final Map<Integer, String> ESCAPES = Map.of((int) '\\', "\\\\", (int) '\n', "\\n", (int) '\r', "\\r",
			(int) '\t', "\\t");

	private static String fixed(String digits, int point) {
		if (point <= 0) {
			return "0." + "0".repeat(-point) + digits;
		}
		if (point >= digits.length()) {
			return digits + "0".repeat(point - digits.length()) + ".0";
		}
		return digits.substring(0, point) + "." + digits.substring(point);
	}

	/**
	 * {@code e+16}, {@code e-05}: a sign and at least two digits, as C's printf writes
	 * them.
	 */
	static String exponent(int e) {
		return "e" + (e < 0 ? "-" : "+") + (Math.abs(e) < 10 ? "0" : "") + Math.abs(e);
	}

}
